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

import org.apache.hadoop.io.Writable;
import org.apache.hama.commons.math.FloatVector;

public interface NeuronInterface {

  /**
   * This method is called when the messages are propagated from the next layer.
   * It can be used to calculate the activation or intermediate output.
   * 
   * @param input messages
   * @throws IOException
   */
  public void forward(FloatVector inputVector) throws IOException;

  /**
   * This method is called when the errors are propagated from the previous
   * layer. It can be used to calculate the error of each neuron and change the
   * weights.
   * 
   * @param messages
   * @throws IOException
   */
  public void backward(FloatVector deltaVector) throws IOException;

}
