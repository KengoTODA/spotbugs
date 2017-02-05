package edu.umd.cs.findbugs.ba;

import javax.annotation.Nonnull;

import edu.umd.cs.findbugs.classfile.engine.bcel.CFGIgnoringAssertFactory;

public class CFGIgnoringAssert extends CFG {
    /**
     * Copy constructor to generate instance from {@link CFG}
     * @param source
     *      {@link CFG} instance to copy
     * @see CFGIgnoringAssertFactory
     */
    @Nonnull
    public CFGIgnoringAssert(@Nonnull CFG source) {
        super(source);
    }
}
