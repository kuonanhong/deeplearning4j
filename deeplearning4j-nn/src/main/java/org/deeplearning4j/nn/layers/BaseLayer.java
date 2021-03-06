/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.nn.layers;

import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.exception.DL4JInvalidInputException;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.nn.params.PretrainParamInitializer;
import org.deeplearning4j.optimize.Solver;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.util.Dropout;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.lang.reflect.Constructor;
import java.util.*;

/**
 * A layer with a bias
 * and activation function
 * @author Adam Gibson
 */
public abstract class BaseLayer<LayerConfT extends org.deeplearning4j.nn.conf.layers.Layer> implements Layer {

    protected INDArray input;
    protected INDArray paramsFlattened;
    protected INDArray gradientsFlattened;
    protected Map<String, INDArray> params;
    protected transient Map<String, INDArray> gradientViews;
    protected NeuralNetConfiguration conf;
    protected INDArray dropoutMask;
    protected boolean dropoutApplied = false;
    protected double score = 0.0;
    protected ConvexOptimizer optimizer;
    protected Gradient gradient;
    protected Collection<IterationListener> iterationListeners = new ArrayList<>();
    protected int index = 0;
    protected INDArray maskArray;
    protected MaskState maskState;
    protected Solver solver;

    public BaseLayer(NeuralNetConfiguration conf) {
        this.conf = conf;
    }

    public BaseLayer(NeuralNetConfiguration conf, INDArray input) {
        this.input = input;
        this.conf = conf;
    }

    protected LayerConfT layerConf() {
        return (LayerConfT) this.conf.getLayer();
    }

    protected String layerId(){
        String name = this.conf().getLayer().getLayerName();
        return "(layer name: " + (name == null ? "\"\"" : name) + ", layer index: " + index + ")";
    }

    public INDArray getInput() {
        return input;
    }

    /**
     * Init the model
     */
    @Override
    public void init() {

    }

    @Override
    public void setInput(INDArray input) {
        this.input = input;
        dropoutApplied = false;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @Override
    public void setIndex(int index) {
        this.index = index;
    }


    @Override
    public Collection<IterationListener> getListeners() {
        return iterationListeners;
    }

    @Override
    public void setListeners(Collection<IterationListener> listeners) {
        this.iterationListeners = listeners != null ? listeners : new ArrayList<IterationListener>();
    }

    @Override
    public void setListeners(IterationListener... listeners) {
        setListeners(Arrays.asList(listeners));
    }

    @Override
    public Gradient error(INDArray errorSignal) {
        INDArray W = getParam(DefaultParamInitializer.WEIGHT_KEY);
        Gradient nextLayerGradient = new DefaultGradient();
        INDArray wErrorSignal = errorSignal.mmul(W.transpose());
        nextLayerGradient.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, wErrorSignal);
        return nextLayerGradient;
    }

    @Override @Deprecated
    public INDArray derivativeActivation(INDArray input) {
        throw new UnsupportedOperationException("Deprecated - " + layerId());
    }

    @Override
    public Gradient calcGradient(Gradient layerError, INDArray activation) {
        Gradient ret = new DefaultGradient();
        INDArray weightErrorSignal = layerError.getGradientFor(DefaultParamInitializer.WEIGHT_KEY);
        INDArray weightError = weightErrorSignal.transpose().mmul(activation).transpose();
        ret.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, weightError);
        INDArray biasGradient = weightError.mean(0);
        ret.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, biasGradient);

        return ret;
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon) {
        //If this layer is layer L, then epsilon is (w^(L+1)*(d^(L+1))^T) (or equivalent)
        INDArray z = preOutput(true); //Note: using preOutput(INDArray) can't be used as this does a setInput(input) and resets the 'appliedDropout' flag
        //INDArray activationDerivative = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(conf().getLayer().getActivationFunction(), z).derivative());
        //        INDArray activationDerivative = conf().getLayer().getActivationFn().getGradient(z);
        //        INDArray delta = epsilon.muli(activationDerivative);
        INDArray delta = conf().getLayer().getActivationFn().backprop(z, epsilon).getFirst(); //TODO handle activation function params

        if (maskArray != null) {
            applyMask(delta);
        }

        Gradient ret = new DefaultGradient();

        INDArray weightGrad = gradientViews.get(DefaultParamInitializer.WEIGHT_KEY); //f order
        Nd4j.gemm(input, delta, weightGrad, true, false, 1.0, 0.0);
        INDArray biasGrad = gradientViews.get(DefaultParamInitializer.BIAS_KEY);
        delta.sum(biasGrad, 0); //biasGrad is initialized/zeroed first

        ret.gradientForVariable().put(DefaultParamInitializer.WEIGHT_KEY, weightGrad);
        ret.gradientForVariable().put(DefaultParamInitializer.BIAS_KEY, biasGrad);

        INDArray epsilonNext = params.get(DefaultParamInitializer.WEIGHT_KEY).mmul(delta.transpose()).transpose();

        return new Pair<>(ret, epsilonNext);
    }

    public void fit() {
        fit(this.input);
    }

    @Override
    public void computeGradientAndScore() {
        if (this.input == null)
            return;

        INDArray output = activate(true);
        setScoreWithZ(output);

    }


    protected void setScoreWithZ(INDArray z) {}


    @Override
    public INDArray preOutput(INDArray x, TrainingMode training) {
        return preOutput(x, training == TrainingMode.TRAIN);
    }

    @Override
    public INDArray activate(TrainingMode training) {
        return activate(training == TrainingMode.TRAIN);
    }

    @Override
    public INDArray activate(INDArray input, TrainingMode training) {
        return activate(input, training == TrainingMode.TRAIN);
    }

    /**
     * Objective function:  the specified objective
     * @return the score for the objective
     */

    @Override
    public double score() {
        return score;
    }

    @Override
    public Gradient gradient() {
        return gradient;
    }

    /**
     * iterate one iteration of the network
     *
     * @param input  the input to iterate on
     */
    @Override
    public void iterate(INDArray input) {
        setInput(input.dup());
        applyDropOutIfNecessary(true);
        Gradient gradient = gradient();
        for (String paramType : gradient.gradientForVariable().keySet()) {
            update(gradient.getGradientFor(paramType), paramType);
        }
    }

    @Override
    public void update(Gradient gradient) {
        for (String paramType : gradient.gradientForVariable().keySet()) {
            update(gradient.getGradientFor(paramType), paramType);
        }
    }

    @Override
    public void update(INDArray gradient, String paramType) {
        setParam(paramType, getParam(paramType).addi(gradient));
    }


    @Override
    public ConvexOptimizer getOptimizer() {
        if (optimizer == null) {
            Solver solver = new Solver.Builder().model(this).configure(conf()).build();
            this.optimizer = solver.getOptimizer();
        }
        return optimizer;
    }

    @Override
    public void setConf(NeuralNetConfiguration conf) {
        this.conf = conf;
    }

    /**Returns the parameters of the neural network as a flattened row vector
     * @return the parameters of the neural network
     */
    @Override
    public INDArray params() {
        return paramsFlattened;
    }

    @Override
    public INDArray getParam(String param) {
        return params.get(param);
    }

    @Override
    public void setParam(String key, INDArray val) {
        if (params.containsKey(key))
            params.get(key).assign(val);
        else
            params.put(key, val);
    }

    @Override
    public void setParams(INDArray params) {
        if (params == paramsFlattened)
            return; //no op
        setParams(params, 'f');
    }

    protected void setParams(INDArray params, char order) {
        List<String> parameterList = conf.variables();
        int length = 0;
        for (String s : parameterList)
            length += getParam(s).length();
        if (params.length() != length)
            throw new IllegalArgumentException("Unable to set parameters: must be of length " + length
                    + ", got params of length " + params.length() + " - " + layerId());
        int idx = 0;
        Set<String> paramKeySet = this.params.keySet();
        for (String s : paramKeySet) {
            INDArray param = getParam(s);
            INDArray get = params.get(NDArrayIndex.point(0), NDArrayIndex.interval(idx, idx + param.length()));
            if (param.length() != get.length())
                throw new IllegalStateException("Parameter " + s + " should have been of length " + param.length()
                                + " but was " + get.length() + " - " + layerId());
            param.assign(get.reshape(order, param.shape())); //Use assign due to backprop params being a view of a larger array
            idx += param.length();
        }
    }

    @Override
    public void setParamsViewArray(INDArray params) {
        if (this.params != null && params.length() != numParams())
            throw new IllegalArgumentException("Invalid input: expect params of length " + numParams()
                            + ", got params of length " + params.length() + " - " + layerId());

        this.paramsFlattened = params;
    }

    @Override
    public INDArray getGradientsViewArray() {
        return gradientsFlattened;
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray gradients) {
        if (this.params != null && gradients.length() != numParams())
            throw new IllegalArgumentException("Invalid input: expect gradients array of length " + numParams(true)
                            + ", got array of length " + gradients.length() + " - " + layerId());

        this.gradientsFlattened = gradients;
        this.gradientViews = conf.getLayer().initializer().getGradientsFromFlattened(conf, gradients);
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {
        this.params = paramTable;
    }

    @Override
    public void initParams() {
        throw new UnsupportedOperationException("Deprecated - no longer used - " + layerId());
    }

    @Override
    public Map<String, INDArray> paramTable() {
        return paramTable(false);
    }

    @Override
    public Map<String, INDArray> paramTable(boolean backpropParamsOnly) {
        return params;
    }

    @Override
    public INDArray preOutput(INDArray x, boolean training) {
        if (x == null) {
            throw new IllegalArgumentException("Cannot do forward pass with null input " + layerId());
        }
        setInput(x);
        return preOutput(training);
    }

    public INDArray preOutput(boolean training) {
        applyDropOutIfNecessary(training);
        INDArray b = getParam(DefaultParamInitializer.BIAS_KEY);
        INDArray W = getParam(DefaultParamInitializer.WEIGHT_KEY);

        //Input validation:
        if (input.rank() != 2 || input.columns() != W.rows()) {
            if (input.rank() != 2) {
                throw new DL4JInvalidInputException("Input that is not a matrix; expected matrix (rank 2), got rank "
                                + input.rank() + " array with shape " + Arrays.toString(input.shape())
                        + ". Missing preprocessor or wrong input type? " + layerId());
            }
            throw new DL4JInvalidInputException("Input size (" + input.columns() + " columns; shape = "
                            + Arrays.toString(input.shape())
                            + ") is invalid: does not match layer input size (layer # inputs = " + W.size(0)
                    + ") " + layerId());
        }

        if (conf.isUseDropConnect() && training && conf.getLayer().getDropOut() > 0) {
            W = Dropout.applyDropConnect(this, DefaultParamInitializer.WEIGHT_KEY);
        }

        INDArray ret = input.mmul(W).addiRowVector(b);

        if (maskArray != null) {
            applyMask(ret);
        }

        return ret;
    }

    protected void applyMask(INDArray to) {
        to.muliColumnVector(maskArray);
    }

    @Override
    public INDArray activate(boolean training) {
        INDArray z = preOutput(training);
        //INDArray ret = Nd4j.getExecutioner().execAndReturn(Nd4j.getOpFactory().createTransform(
        //        conf.getLayer().getActivationFunction(), z, conf.getExtraArgs() ));
        INDArray ret = conf().getLayer().getActivationFn().getActivation(z, training);

        if (maskArray != null) {
            applyMask(ret);
        }

        return ret;
    }

    @Override
    public INDArray activate(INDArray input) {
        setInput(input);
        return activate(true);
    }

    @Override
    public INDArray activate(INDArray input, boolean training) {
        setInput(input);
        return activate(training);
    }

    @Override
    public INDArray activate() {
        return activate(false);
    }


    /**
     * Classify input
     * @param x the input (can either be a matrix or vector)
     * If it's a matrix, each row is considered an example
     * and associated rows are classified accordingly.
     * Each row will be the likelihood of a label given that example
     * @return a probability distribution for each row
     */
    @Override
    public INDArray preOutput(INDArray x) {
        return preOutput(x, true);
    }

    @Override
    public double calcL2(boolean backpropParamsOnly) {
        if (!conf.isUseRegularization())
            return 0.0;

        //L2 norm: sqrt( sum_i x_i^2 ) -> want sum squared weights, so l2 norm squared
        double l2Sum = 0.0;
        if (conf.getL2ByParam(DefaultParamInitializer.WEIGHT_KEY) > 0.0) {
            double l2Norm = getParam(DefaultParamInitializer.WEIGHT_KEY).norm2Number().doubleValue();
            l2Sum += 0.5 * conf.getL2ByParam(DefaultParamInitializer.WEIGHT_KEY) * l2Norm * l2Norm;
        }
        if (conf.getL2ByParam(DefaultParamInitializer.BIAS_KEY) > 0.0) {
            double l2Norm = getParam(DefaultParamInitializer.BIAS_KEY).norm2Number().doubleValue();
            l2Sum += 0.5 * conf.getL2ByParam(DefaultParamInitializer.BIAS_KEY) * l2Norm * l2Norm;
        }
        return l2Sum;
    }

    @Override
    public double calcL1(boolean backpropParamsOnly) {
        if (!conf.isUseRegularization())
            return 0.0;
        double l1Sum = 0.0;
        if (conf.getL1ByParam(DefaultParamInitializer.WEIGHT_KEY) > 0.0) {
            l1Sum += conf.getL1ByParam(DefaultParamInitializer.WEIGHT_KEY)
                            * getParam(DefaultParamInitializer.WEIGHT_KEY).norm1Number().doubleValue();
        }
        if (conf.getL1ByParam(DefaultParamInitializer.BIAS_KEY) > 0.0) {
            l1Sum += conf.getL1ByParam(DefaultParamInitializer.BIAS_KEY)
                            * getParam(DefaultParamInitializer.BIAS_KEY).norm1Number().doubleValue();
        }
        return l1Sum;
    }

    @Override
    public int batchSize() {
        return input.size(0);
    }


    @Override
    public INDArray activationMean() {
        INDArray b = getParam(DefaultParamInitializer.BIAS_KEY);
        INDArray W = getParam(DefaultParamInitializer.WEIGHT_KEY);
        return input().mmul(W).addiRowVector(b);
    }

    @Override
    public NeuralNetConfiguration conf() {
        return conf;
    }


    @Override
    public void clear() {
        if (input != null) {
            // NONONO
            //input.data().destroy();
            input = null;
        }

    }

    protected void applyDropOutIfNecessary(boolean training) {
        if (conf.getLayer().getDropOut() > 0 && !conf.isUseDropConnect() && training && !dropoutApplied) {
            if (Nd4j.getWorkspaceManager().checkIfWorkspaceExists(ComputationGraph.workspaceExternal)) {
                try (MemoryWorkspace ws = Nd4j.getWorkspaceManager()
                                .getWorkspaceForCurrentThread(ComputationGraph.workspaceExternal)
                                .notifyScopeBorrowed()) {
                    input = input.isView() ? input.dup() : input.unsafeDuplication();
                }
            } else
                input = input.isView() ? input.dup() : input.unsafeDuplication();

            Dropout.applyDropout(input, conf.getLayer().getDropOut());
            dropoutApplied = true;
        }
    }

    /**
     * Averages the given logistic regression from a mini batch into this layer
     * @param l the logistic regression layer to average into this layer
     * @param batchSize  the batch size
     */
    @Override
    public void merge(Layer l, int batchSize) {
        setParams(params().addi(l.params().divi(batchSize)));
        computeGradientAndScore();
    }

    @Override
    public Layer clone() {
        Layer layer = null;
        try {
            Constructor c = getClass().getConstructor(NeuralNetConfiguration.class);
            layer = (Layer) c.newInstance(conf);
            Map<String, INDArray> linkedTable = new LinkedHashMap<>();
            for (Map.Entry<String, INDArray> entry : params.entrySet()) {
                linkedTable.put(entry.getKey(), entry.getValue().dup());
            }
            layer.setParamTable(linkedTable);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return layer;

    }

    @Override
    public Type type() {
        return Type.FEED_FORWARD;
    }

    /**
     * The number of parameters for the model
     *
     * @return the number of parameters for the model
     */
    @Override
    public int numParams() {
        int ret = 0;
        for (INDArray val : params.values())
            ret += val.length();
        return ret;
    }

    @Override
    public int numParams(boolean backwards) {
        return numParams();
    }

    @Override
    public void fit(INDArray input) {
        if (input != null) {
            setInput(input.dup());
            applyDropOutIfNecessary(true);
        }
        if (solver == null) {
            solver = new Solver.Builder().model(this).configure(conf()).listeners(getListeners()).build();
            //Set the updater state view array. For MLN and CG, this is done by MultiLayerUpdater and ComputationGraphUpdater respectively
            Updater updater = solver.getOptimizer().getUpdater();
            int updaterStateSize = (int)conf().getLayer().getIUpdater().stateSize(numParams());
            if (updaterStateSize > 0)
                updater.setStateViewArray(this, Nd4j.createUninitialized(new int[] {1, updaterStateSize}, Nd4j.order()),
                                true);
        }
        this.optimizer = solver.getOptimizer();
        solver.optimize();
    }


    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(), score());
    }

    @Override
    public INDArray input() {
        return input;
    }

    @Override
    public void validateInput() {

    }

    @Override
    public String toString() {
        return getClass().getName() + "{" + "conf=" + conf + ", dropoutMask=" + dropoutMask + ", score=" + score
                        + ", optimizer=" + optimizer + ", listeners=" + iterationListeners + '}';
    }

    @Override
    public Layer transpose() {
        if (!(conf.getLayer() instanceof org.deeplearning4j.nn.conf.layers.FeedForwardLayer))
            throw new UnsupportedOperationException("Unsupported layer type: " + conf.getLayer().getClass().getName()
                    + " - " + layerId());

        INDArray w = getParam(DefaultParamInitializer.WEIGHT_KEY);
        INDArray b = getParam(DefaultParamInitializer.BIAS_KEY);
        INDArray vb = getParam(PretrainParamInitializer.VISIBLE_BIAS_KEY);
        Layer layer;
        try {
            NeuralNetConfiguration clone = conf.clone(); // assume a deep clone here

            org.deeplearning4j.nn.conf.layers.FeedForwardLayer clonedLayerConf =
                            (org.deeplearning4j.nn.conf.layers.FeedForwardLayer) clone.getLayer();
            int nIn = clonedLayerConf.getNOut();
            int nOut = clonedLayerConf.getNIn();
            clonedLayerConf.setNIn(nIn);
            clonedLayerConf.setNOut(nOut);

            //Need to swap the hidden and visible biases for pretrain layers
            INDArray newB;
            INDArray newVB = null;

            int totalParams = w.length();
            if (vb != null) {
                newB = vb.dup();
                newVB = b.dup();
                totalParams += newB.length() + newVB.length();
            } else {
                newB = Nd4j.create(1, nOut);
                totalParams += newB.length();
            }

            INDArray paramsView = Nd4j.create(1, totalParams);
            layer = clone.getLayer().instantiate(clone, iterationListeners, this.index, paramsView, true);

            layer.setParam(DefaultParamInitializer.WEIGHT_KEY, w.transpose().dup());
            layer.setParam(DefaultParamInitializer.BIAS_KEY, newB);
            if (vb != null)
                layer.setParam(PretrainParamInitializer.VISIBLE_BIAS_KEY, newVB);
        } catch (Exception e) {
            throw new RuntimeException("Unable to construct transposed layer: " + layerId(), e);
        }

        return layer;
    }

    @Override
    public void accumulateScore(double accum) {
        score += accum;
    }

    @Override
    public void setInputMiniBatchSize(int size) {}

    @Override
    public int getInputMiniBatchSize() {
        return input.size(0);
    }

    @Override
    public void applyLearningRateScoreDecay() {
        for (Map.Entry<String, Double> lrPair : conf.getLearningRateByParam().entrySet())
            conf.setLearningRateByParam(lrPair.getKey(),
                            lrPair.getValue() * (conf.getLrPolicyDecayRate() + Nd4j.EPS_THRESHOLD));
    }

    @Override
    public void setMaskArray(INDArray maskArray) {
        this.maskArray = maskArray;
    }

    @Override
    public INDArray getMaskArray() {
        return maskArray;
    }


    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState,
                    int minibatchSize) {
        //Most layers: CNN, dense, activation, etc - set mask array, mask state and then leave the mask unmodified

        this.maskArray = maskArray;
        this.maskState = currentMaskState;

        return new Pair<>(maskArray, currentMaskState);
    }
}
