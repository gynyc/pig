/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POFRJoin;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POMergeJoin.TuplesToSchemaTupleList;
import org.apache.pig.data.SchemaTupleBackend;
import org.apache.pig.data.SchemaTupleClassGenerator.GenContext;
import org.apache.pig.data.SchemaTupleFactory;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.io.PigNullableWritable;
import org.apache.pig.impl.logicalLayer.schema.Schema;
import org.apache.tez.runtime.api.LogicalInput;
import org.apache.tez.runtime.library.broadcast.input.BroadcastKVReader;
import org.apache.tez.runtime.library.input.ShuffledUnorderedKVInput;

import com.google.common.collect.Lists;

/**
 * POFRJoinTez is used on the backend to load replicated table from Tez
 * ShuffleUnorderedKVInput and load fragmented table from data pipeline.
 */
public class POFRJoinTez extends POFRJoin implements TezLoad {

    private static final Log log = LogFactory.getLog(POFRJoinTez.class);
    private static final long serialVersionUID = 1L;

    // For replicated tables
    private List<ShuffledUnorderedKVInput> replInputs = Lists.newArrayList();
    @SuppressWarnings("rawtypes")
    private List<BroadcastKVReader> replReaders = Lists.newArrayList();
    private List<String> inputKeys;
    private int currIdx = 0;

    public POFRJoinTez(POFRJoin copy, List<String> inputKeys) throws ExecException {
       super(copy);
       this.inputKeys = inputKeys;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void attachInputs(Map<String, LogicalInput> inputs, Configuration conf)
            throws ExecException {
        try {
            for (String key : inputKeys) {
                LogicalInput input = inputs.get(key);
                if (input instanceof ShuffledUnorderedKVInput) {
                    ShuffledUnorderedKVInput suInput = (ShuffledUnorderedKVInput) input;
                    this.replInputs.add(suInput);
                    this.replReaders.add((BroadcastKVReader) suInput.getReader());
                }
            }
        } catch (Exception e) {
            throw new ExecException(e);
        }
    }

    /**
     * Builds the HashMaps by reading replicated inputs from broadcast edges
     *
     * @throws ExecException
     */
    @Override
    protected void setUpHashMap() throws ExecException {
        SchemaTupleFactory[] inputSchemaTupleFactories = new SchemaTupleFactory[inputSchemas.length];
        SchemaTupleFactory[] keySchemaTupleFactories = new SchemaTupleFactory[inputSchemas.length];

        for (int i = 0; i < inputSchemas.length; i++) {
            Schema schema = inputSchemas[i];
            if (schema != null) {
                log.debug("Using SchemaTuple for FR Join Schema: " + schema);
                inputSchemaTupleFactories[i] =
                        SchemaTupleBackend.newSchemaTupleFactory(schema, false, GenContext.FR_JOIN);
            }
            schema = keySchemas[i];
            if (schema != null) {
                log.debug("Using SchemaTuple for FR Join key Schema: " + schema);
                keySchemaTupleFactories[i] =
                        SchemaTupleBackend.newSchemaTupleFactory(schema, false, GenContext.FR_JOIN);
            }
        }

        long time1 = System.currentTimeMillis();
        log.debug("Completed setup. Trying to build replication hash table");

        replicates[fragment] = null;
        while (currIdx < replInputs.size()) {
            // We need to adjust the index because the number of replInputs is
            // one less than the number of inputSchemas. The inputSchemas
            // includes the fragmented table.
            int adjustedIdx = currIdx == fragment ? currIdx + 1 : currIdx;
            SchemaTupleFactory inputSchemaTupleFactory = inputSchemaTupleFactories[adjustedIdx];
            SchemaTupleFactory keySchemaTupleFactory = keySchemaTupleFactories[adjustedIdx];

            TupleToMapKey replicate = new TupleToMapKey(1000, keySchemaTupleFactory);
            POLocalRearrange lr = LRs[adjustedIdx];

            try {
                while (replReaders.get(currIdx).next()) {
                    if (getReporter() != null) {
                        getReporter().progress();
                    }

                    PigNullableWritable key = (PigNullableWritable) replReaders.get(currIdx).getCurrentKey();
                    if (isKeyNull(key.getValueAsPigType())) continue;
                    NullableTuple val = (NullableTuple) replReaders.get(currIdx).getCurrentValue();

                    // POFRJoin#getValueTuple() is reused to construct valTuple,
                    // and it takes an indexed Tuple as parameter. So we need to
                    // construct one here.
                    Tuple retTuple = mTupleFactory.newTuple(3);
                    retTuple.set(0, key.getIndex());
                    retTuple.set(1, key.getValueAsPigType());
                    retTuple.set(2, val.getValueAsPigType());
                    Tuple valTuple = getValueTuple(lr, retTuple);

                    Tuple keyTuple = mTupleFactory.newTuple(1);
                    keyTuple.set(0, key.getValueAsPigType());
                    if (replicate.get(keyTuple) == null) {
                        replicate.put(keyTuple, new TuplesToSchemaTupleList(1, inputSchemaTupleFactory));
                    }
                    replicate.get(keyTuple).add(valTuple);
                }
            } catch (IOException e) {
                throw new ExecException(e);
            }
            replicates[adjustedIdx] = replicate;
            currIdx++;
        }

        long time2 = System.currentTimeMillis();
        log.info("Hash Table built. Time taken: " + (time2 - time1));
    }

    @Override
    public String name() {
        StringBuffer inputs = new StringBuffer();
        for (int i = 0; i < inputKeys.size(); i++) {
            if (i > 0) inputs.append(",");
            inputs.append(inputKeys.get(i));
        }
        return super.name() + "\t<-\t " + inputs.toString();
    }
}