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
package org.apache.horn.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.sync.SyncException;
import org.apache.hama.commons.io.VectorWritable;
import org.apache.hama.commons.math.DenseDoubleMatrix;
import org.apache.hama.commons.math.DoubleMatrix;
import org.apache.hama.commons.math.DoubleVector;
import org.apache.hama.ipc.RPC;

import com.google.common.base.Preconditions;

/**
 * The trainer that train the {@link LayeredNeuralNetwork} based on BSP
 * framework.
 * 
 */
public final class LayeredNeuralNetworkTrainer
    extends
    BSP<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> {

  private static final Log LOG = LogFactory
      .getLog(LayeredNeuralNetworkTrainer.class);

  /* When given peer is master worker: base of parameter merge */
  /* When given peer is slave worker: neural network for training */
  private LayeredNeuralNetwork inMemoryModel;

  /* Job configuration */
  private HamaConfiguration conf;

  /* Default batch size */
  private int batchSize;

  /* whether it is converging or not */
  private AtomicBoolean isConverge;

  /* When given peer is master worker: Asynchronous parameter merger */
  /* When given peer is slave worker: null */
  private RPC.Server merger;

  /* When given peer is master worker: null */
  /* When given peer is slave worker: proxy to Asynchronous parameter merger */
  private ParameterMerger proxy;

  /**
   * Returns true if this worker is master worker.
   *
   * @param peer
   * */
  private boolean isMaster(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer) {
    return peer.getPeerIndex() == peer.getNumPeers() - 1;
  }

  @Override
  /**
   * If the model path is specified, load the existing from storage location.
   */
  public void setup(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer) {
    // At least one master & slave worker exist.
    Preconditions.checkArgument(peer.getNumPeers() >= 2);
    this.conf = peer.getConfiguration();

    String modelPath = conf.get("model.path");
    this.inMemoryModel = new LayeredNeuralNetwork(conf, modelPath);

    this.batchSize = conf.getInt("training.batch.size", 50);
    this.isConverge = new AtomicBoolean(false);

    int slaveCount = peer.getNumPeers() - 1;
    int mergeLimit = conf.getInt("training.max.iterations", 100000);
    int convergenceCheckInterval = peer.getNumPeers()
        * conf.getInt("convergence.check.interval", 2000);
    String master = peer.getPeerName();
    String masterAddr = master.substring(0, master.indexOf(':'));
    int port = conf.getInt("sync.server.port", 40089);

    if (isMaster(peer)) {
      try {
        this.merger = RPC.getServer(new ParameterMergerServer(inMemoryModel,
            isConverge, slaveCount, mergeLimit, convergenceCheckInterval),
            masterAddr, port, slaveCount, false, conf);
        merger.start();
      } catch (IOException e) {
        e.printStackTrace();
      }
      LOG.info("Begin to train");
    } else {
      InetSocketAddress addr = new InetSocketAddress(masterAddr, port);
      try {
        this.proxy = (ParameterMerger) RPC.getProxy(ParameterMerger.class,
            ParameterMerger.versionID, addr, conf);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  /**
   * Write the trained model back to stored location.
   */
  public void cleanup(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer) {
    // write model to modelPath
    if (isMaster(peer)) {
      try {
        LOG.info("Write model back to " + inMemoryModel.getModelPath());
        this.inMemoryModel.writeModelToFile();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public void bsp(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer)
      throws IOException, SyncException, InterruptedException {
    while (!this.isConverge.get()) {
      // each slave-worker calculate the matrices updates according to local
      // data
      // and merge them with master
      if (!isMaster(peer)) {
        calculateUpdates(peer);
      }
    }
    peer.sync();
    if (isMaster(peer)) {
      merger.stop();
    }
    peer.sync(); // finalize the bsp program.
  }

  /**
   * Calculate the matrices updates according to local partition of data.
   * 
   * @param peer
   * @throws IOException
   */
  private void calculateUpdates(
      BSPPeer<LongWritable, VectorWritable, NullWritable, NullWritable, ParameterMessage> peer)
      throws IOException {

    DoubleMatrix[] weightUpdates = new DoubleMatrix[this.inMemoryModel.weightMatrixList
        .size()];
    for (int i = 0; i < weightUpdates.length; ++i) {
      int row = this.inMemoryModel.weightMatrixList.get(i).getRowCount();
      int col = this.inMemoryModel.weightMatrixList.get(i).getColumnCount();
      weightUpdates[i] = new DenseDoubleMatrix(row, col);
    }

    // continue to train
    double avgTrainingError = 0.0;
    LongWritable key = new LongWritable();
    VectorWritable value = new VectorWritable();
    for (int recordsRead = 0; recordsRead < batchSize; ++recordsRead) {
      if (!peer.readNext(key, value)) {
        peer.reopenInput();
        peer.readNext(key, value);
      }
      DoubleVector trainingInstance = value.getVector();
      LayeredNeuralNetwork.matricesAdd(weightUpdates,
          this.inMemoryModel.trainByInstance(trainingInstance));
      avgTrainingError += this.inMemoryModel.trainingError;
    }
    avgTrainingError /= batchSize;

    // calculate the average of updates
    for (int i = 0; i < weightUpdates.length; ++i) {
      weightUpdates[i] = weightUpdates[i].divide(batchSize);
    }

    // exchange parameter update with master
    ParameterMessage msg = new ParameterMessage(
        avgTrainingError, false, weightUpdates,
        this.inMemoryModel.getPrevMatricesUpdates());

    ParameterMessage inMessage = proxy.merge(msg);
    DoubleMatrix[] newWeights = inMessage.getCurMatrices();
    DoubleMatrix[] preWeightUpdates = inMessage.getPrevMatrices();
    this.inMemoryModel.setWeightMatrices(newWeights);
    this.inMemoryModel.setPrevWeightMatrices(preWeightUpdates);
    this.isConverge.set(inMessage.isConverge());
  }

}
