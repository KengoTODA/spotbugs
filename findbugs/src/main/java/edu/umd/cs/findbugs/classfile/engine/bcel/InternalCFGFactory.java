package edu.umd.cs.findbugs.classfile.engine.bcel;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.GETSTATIC;
import org.apache.bcel.generic.IFNE;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.MethodGen;

import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.AnalysisFeatures;
import edu.umd.cs.findbugs.ba.BasicBlock;
import edu.umd.cs.findbugs.ba.CFG;
import edu.umd.cs.findbugs.ba.CFGBuilder;
import edu.umd.cs.findbugs.ba.CFGBuilderFactory;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.DepthFirstSearch;
import edu.umd.cs.findbugs.ba.Edge;
import edu.umd.cs.findbugs.ba.EdgeTypes;
import edu.umd.cs.findbugs.ba.JavaClassAndMethod;
import edu.umd.cs.findbugs.ba.MethodUnprofitableException;
import edu.umd.cs.findbugs.ba.MissingClassException;
import edu.umd.cs.findbugs.ba.PruneInfeasibleExceptionEdges;
import edu.umd.cs.findbugs.ba.PruneUnconditionalExceptionThrowerEdges;
import edu.umd.cs.findbugs.ba.SignatureConverter;
import edu.umd.cs.findbugs.ba.type.TypeDataflow;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.classfile.Global;
import edu.umd.cs.findbugs.classfile.IAnalysisCache;
import edu.umd.cs.findbugs.classfile.MethodDescriptor;

/**
 * <p>An internal factory to share the {@link CFG} instantiation logic among multiple factories.</p>
 * 
 * @see CFGFactory
 */
@ThreadSafe
class InternalCFGFactory {
    private static final boolean DEBUG_CFG = SystemProperties.getBoolean("classContext.debugCFG");

    @Nonnull
    CFG analyze(@Nonnull IAnalysisCache analysisCache, @Nonnull MethodDescriptor descriptor, boolean assumeAssertionsEnabled) throws CheckedAnalysisException {
        // Construct the CFG in its raw form
        MethodGen methodGen = analysisCache.getMethodAnalysis(MethodGen.class, descriptor);
        if (methodGen == null) {
            JavaClass jclass = analysisCache.getClassAnalysis(JavaClass.class, descriptor.getClassDescriptor());
            Method method = analysisCache.getMethodAnalysis(Method.class, descriptor);
            JavaClassAndMethod javaClassAndMethod = new JavaClassAndMethod(jclass, method);
            AnalysisContext.currentAnalysisContext().getLookupFailureCallback().reportSkippedAnalysis(descriptor);
            throw new MethodUnprofitableException(javaClassAndMethod);
        }
        CFGBuilder cfgBuilder = CFGBuilderFactory.create(descriptor, methodGen);
        cfgBuilder.build();
        CFG cfg = cfgBuilder.getCFG();

        // Mark as busy while we're pruning the CFG.
        cfg.setFlag(CFG.BUSY);

        // Important: eagerly put the CFG in the analysis cache.
        // Recursively performed analyses required to prune the CFG,
        // such as TypeAnalysis, will operate on the raw CFG.
        analysisCache.eagerlyPutMethodAnalysis(CFG.class, descriptor, cfg);

        // Record method name and signature for informational purposes
        cfg.setMethodName(SignatureConverter.convertMethodSignature(methodGen));
        cfg.setMethodGen(methodGen);

        // System.out.println("CC: getting refined CFG for " + methodId);
        if (DEBUG_CFG) {
            String methodId = methodGen.getClassName() + "." + methodGen.getName() + ":" + methodGen.getSignature();
            System.out.println("CC: getting refined CFG for " + methodId);
        }
        if (ClassContext.DEBUG) {
            String methodId = methodGen.getClassName() + "." + methodGen.getName() + ":" + methodGen.getSignature();
            System.out.println("ClassContext: request to prune " + methodId);
        }

        // Remove CFG edges corresponding to failed assertions.
        boolean changed = false;
        if (assumeAssertionsEnabled) {
            LinkedList<Edge> edgesToRemove = new LinkedList<Edge>();
            for (Iterator<Edge> i = cfg.edgeIterator(); i.hasNext();) {
                Edge e = i.next();
                if (e.getType() == EdgeTypes.IFCMP_EDGE) {
                    try {
                        BasicBlock source = e.getSource();
                        InstructionHandle last = source.getLastInstruction();
                        Instruction lastInstruction = last.getInstruction();
                        InstructionHandle prev = last.getPrev();
                        Instruction prevInstruction = prev.getInstruction();
                        if (prevInstruction instanceof GETSTATIC && lastInstruction instanceof IFNE) {
                            GETSTATIC getStatic = (GETSTATIC) prevInstruction;

                            if ("$assertionsDisabled".equals(getStatic.getFieldName(methodGen.getConstantPool()))
                                    && "Z".equals(getStatic.getSignature(methodGen.getConstantPool()))) {
                                edgesToRemove.add(e);
                            }
                        }
                    } catch (RuntimeException exception) {
                        assert true; // ignore it
                    }
                }
            }
            if (edgesToRemove.size() > 0) {
                changed = true;
                for (Edge e : edgesToRemove) {
                    cfg.removeEdge(e);
                }
            }
        }
        cfg.setFlag(CFG.PRUNED_FAILED_ASSERTION_EDGES);

        final boolean PRUNE_INFEASIBLE_EXCEPTION_EDGES = AnalysisContext.currentAnalysisContext().getBoolProperty(
                AnalysisFeatures.ACCURATE_EXCEPTIONS);

        if (PRUNE_INFEASIBLE_EXCEPTION_EDGES && !cfg.isFlagSet(CFG.PRUNED_INFEASIBLE_EXCEPTIONS)) {
            try {
                TypeDataflow typeDataflow = analysisCache.getMethodAnalysis(TypeDataflow.class, descriptor);
                // Exception edge pruning based on ExceptionSets.
                // Note: this is quite slow.
                PruneInfeasibleExceptionEdges pruner = new PruneInfeasibleExceptionEdges(cfg, methodGen, typeDataflow);
                pruner.execute();
                changed = changed || pruner.wasCFGModified();
            } catch (MissingClassException e) {
                AnalysisContext.currentAnalysisContext().getLookupFailureCallback()
                .reportMissingClass(e.getClassNotFoundException());
            } catch (DataflowAnalysisException e) {
                AnalysisContext.currentAnalysisContext().getLookupFailureCallback()
                .logError("unable to extract type analysis", e);
            } catch (ClassNotFoundException e) {
                AnalysisContext.currentAnalysisContext().getLookupFailureCallback().reportMissingClass(e);
            }
        }
        cfg.setFlag(CFG.PRUNED_INFEASIBLE_EXCEPTIONS);

        final boolean PRUNE_UNCONDITIONAL_EXCEPTION_THROWER_EDGES = !AnalysisContext.currentAnalysisContext().getBoolProperty(
                AnalysisFeatures.CONSERVE_SPACE);

        if (PRUNE_UNCONDITIONAL_EXCEPTION_THROWER_EDGES && !cfg.isFlagSet(CFG.PRUNED_UNCONDITIONAL_THROWERS)) {
            try {
                JavaClass jclass = analysisCache.getClassAnalysis(JavaClass.class, descriptor.getClassDescriptor());
                Method method = analysisCache.getMethodAnalysis(Method.class, descriptor);
                ConstantPoolGen cpg = analysisCache.getClassAnalysis(ConstantPoolGen.class, descriptor.getClassDescriptor());
                TypeDataflow typeDataflow = analysisCache.getMethodAnalysis(TypeDataflow.class, descriptor);

                PruneUnconditionalExceptionThrowerEdges pruner = new PruneUnconditionalExceptionThrowerEdges(jclass, method,
                        methodGen, cfg, cpg, typeDataflow, AnalysisContext.currentAnalysisContext());
                pruner.execute();
                if (pruner.wasCFGModified()) {
                    changed = true;

                }
            } catch (DataflowAnalysisException e) {
                AnalysisContext.logError("Error pruning normal return edges for unconditionally throwing methods for "
                        + descriptor, e);
            }
        }
        cfg.setFlag(CFG.PRUNED_UNCONDITIONAL_THROWERS);

        // Now we are done with the CFG refining process
        cfg.setFlag(CFG.REFINED);
        cfg.clearFlag(CFG.BUSY);

        // If the CFG changed as a result of pruning, purge all analysis results
        // for the method.
        if (changed) {

            DepthFirstSearch dfs = new DepthFirstSearch(cfg);
            dfs.search();
            Collection<BasicBlock> unreachable = dfs.unvisitedVertices();
            if (!unreachable.isEmpty()) {
                if (DEBUG_CFG) {
                    System.out.println("Unreachable blocks");
                }
                for (BasicBlock b : unreachable) {
                    if (DEBUG_CFG) {
                        System.out.println(" removing " + b);
                    }
                    cfg.removeVertex(b);
                }
            }
            Global.getAnalysisCache().purgeMethodAnalyses(descriptor);
        }

        return cfg;
    }
}
