package edu.harvard.hms.dbmi.avillach.mcp.codegen;

/** Renders an {@link AdapterQuery} as code in one language. Output depends only on the arguments, so the same input gives the same code. */
public interface LanguageGenerator {

    /**
     * The language this generator writes.
     *
     * @return the language
     */
    Language language();

    /**
     * Writes the code and its setup.
     *
     * @param query the query and result to write code for
     * @param setup the deployment's connection details and adapter versions
     * @return the code and setup
     */
    GeneratedCode generate(AdapterQuery query, AdapterSetup setup);
}
