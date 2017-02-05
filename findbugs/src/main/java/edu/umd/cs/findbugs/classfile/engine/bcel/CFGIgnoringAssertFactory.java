package edu.umd.cs.findbugs.classfile.engine.bcel;

import edu.umd.cs.findbugs.ba.CFGIgnoringAssert;
import edu.umd.cs.findbugs.classfile.CheckedAnalysisException;
import edu.umd.cs.findbugs.classfile.IAnalysisCache;
import edu.umd.cs.findbugs.classfile.MethodDescriptor;

public class CFGIgnoringAssertFactory extends AnalysisFactory<CFGIgnoringAssert> {
    private final InternalCFGFactory delegate = new InternalCFGFactory();

    /**
     * Constructor.
     */
    public CFGIgnoringAssertFactory() {
        super("control flow graph factory which ignores `assert`", CFGIgnoringAssert.class);
    }

    @Override
    public CFGIgnoringAssert analyze(IAnalysisCache analysisCache, MethodDescriptor descriptor) throws CheckedAnalysisException {
        return new CFGIgnoringAssert(delegate.analyze(analysisCache, descriptor, false));
    }

    @Override
    public void registerWith(IAnalysisCache analysisCache) {
        analysisCache.registerMethodAnalysisEngine(CFGIgnoringAssert.class, this);
    }

}
