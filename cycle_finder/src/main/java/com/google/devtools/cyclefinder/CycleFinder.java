/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.cyclefinder;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.file.RegularInputFile;
import com.google.devtools.j2objc.jdt.JdtParser;
import com.google.devtools.j2objc.pipeline.J2ObjCIncompatibleStripper;
import com.google.devtools.j2objc.translate.LambdaTypeElementAdder;
import com.google.devtools.j2objc.translate.OuterReferenceResolver;
import com.google.devtools.j2objc.util.ErrorUtil;
import com.google.devtools.j2objc.util.FileUtil;
import com.google.devtools.j2objc.util.Parser;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A tool for finding possible reference cycles in a Java program.
 *
 * @author Keith Stanger
 */
public class CycleFinder {

  private final Options options;
  private final NameList blacklist;
  private final List<List<Edge>> cycles = new ArrayList<>();

  static {
    // Enable assertions in the cycle finder.
    ClassLoader loader = CycleFinder.class.getClassLoader();
    if (loader != null) {
      loader.setPackageAssertionStatus(CycleFinder.class.getPackage().getName(), true);
    }
  }

  public CycleFinder(Options options) throws IOException {
    this.options = options;
    com.google.devtools.j2objc.Options.load(new String[] {
      "-encoding", options.fileEncoding(),
      "-source",   options.sourceVersion().flag()
    });
    blacklist = getBlacklist();
  }

  private static Parser createParser(Options options) {
    Parser parser = new JdtParser();
    parser.addSourcepathEntries(Strings.nullToEmpty(options.getSourcepath()));
    parser.addClasspathEntries(Strings.nullToEmpty(options.getBootclasspath()));
    parser.addClasspathEntries(Strings.nullToEmpty(options.getClasspath()));
    parser.setEncoding(options.fileEncoding());
    return parser;
  }

  private static void exitOnErrors() {
    int nErrors = ErrorUtil.errorCount();
    if (nErrors > 0) {
      System.err.println("Failed with " + nErrors + " errors:");
      for (String error : ErrorUtil.getErrorMessages()) {
        System.err.println("error: " + error);
      }
      System.exit(nErrors);
    }
  }

  private void testFileExistence() {
    for (String filePath : options.getSourceFiles()) {
      File f = new File(filePath);
      if (!f.exists()) {
        ErrorUtil.error("File not found: " + filePath);
      }
    }
  }

  private NameList getBlacklist() throws IOException {
    List<String> blackListFiles = options.getBlacklistFiles();
    if (blackListFiles.isEmpty()) {
      return null;
    }
    return NameList.createFromFiles(blackListFiles, options.fileEncoding());
  }

  private File stripIncompatible(
      List<String> sourceFileNames, Parser parser) throws IOException {
    File strippedDir = null;
    for (int i = 0; i < sourceFileNames.size(); i++) {
      String fileName = sourceFileNames.get(i);
      RegularInputFile file = new RegularInputFile(fileName);
      String source = FileUtil.readFile(file);
      if (!source.contains("J2ObjCIncompatible")) {
        continue;
      }
      if (strippedDir == null) {
        strippedDir = Files.createTempDir();
        parser.prependSourcepathEntry(strippedDir.getPath());
      }
      org.eclipse.jdt.core.dom.CompilationUnit unit = parser.parseWithoutBindings(fileName, source);
      String qualifiedName = FileUtil.getQualifiedMainTypeName(file, unit);
      String newSource = J2ObjCIncompatibleStripper.strip(source, unit);
      String relativePath = qualifiedName.replace('.', File.separatorChar) + ".java";
      File strippedFile = new File(strippedDir, relativePath);
      Files.createParentDirs(strippedFile);
      Files.write(newSource, strippedFile, Charset.forName(options.fileEncoding()));
      sourceFileNames.set(i, strippedFile.getPath());
    }
    return strippedDir;
  }

  public List<List<Edge>> findCycles() throws IOException {
    Parser parser = createParser(options);
    NameList whitelist =
        NameList.createFromFiles(options.getWhitelistFiles(), options.fileEncoding());
    final GraphBuilder graphBuilder = new GraphBuilder(whitelist);

    List<String> sourceFiles = options.getSourceFiles();
    File strippedDir = stripIncompatible(sourceFiles, parser);

    Parser.Handler handler = new Parser.Handler() {
      @Override
      public void handleParsedUnit(String path, CompilationUnit unit) {
        new LambdaTypeElementAdder(unit).run();
        new OuterReferenceResolver(unit).run();
        graphBuilder.visitAST(unit);
      }
    };
    parser.parseFiles(sourceFiles, handler, options.sourceVersion());

    FileUtil.deleteTempDir(strippedDir);

    if (ErrorUtil.errorCount() > 0) {
      return null;
    }

    // Construct the graph and find cycles.
    ReferenceGraph graph = graphBuilder.constructGraph().getGraph();
    for (ReferenceGraph component : graph.getStronglyConnectedComponents(getSeedNodes(graph))) {
      handleStronglyConnectedComponent(component);
    }
    return cycles;
  }

  private Set<TypeNode> getSeedNodes(ReferenceGraph graph) {
    if (blacklist == null) {
      return graph.getNodes();
    }
    Set<TypeNode> seedNodes = new HashSet<>();
    for (TypeNode node : graph.getNodes()) {
      if (blacklist.containsType(node)) {
        seedNodes.add(node);
      }
    }
    return seedNodes;
  }

  private void handleStronglyConnectedComponent(ReferenceGraph subgraph) {
    // Make sure to find at least one cycle for each type in the SCC.
    Set<TypeNode> unusedTypes = Sets.newHashSet(subgraph.getNodes());
    while (!unusedTypes.isEmpty()) {
      TypeNode root = Iterables.getFirst(unusedTypes, null);
      assert root != null;
      List<Edge> cycle = subgraph.findShortestCycle(root);
      if (shouldAddCycle(cycle)) {
        cycles.add(cycle);
      }
      for (Edge e : cycle) {
        unusedTypes.remove(e.getOrigin());
      }
    }
  }

  private boolean shouldAddCycle(List<Edge> cycle) {
    if (blacklist == null) {
      return true;
    }
    for (Edge e : cycle) {
      if (blacklist.containsType(e.getOrigin())) {
        return true;
      }
    }
    return false;
  }

  public static void printCycles(Collection<? extends Iterable<Edge>> cycles, PrintStream out) {
    for (Iterable<Edge> cycle : cycles) {
      out.println();
      out.println("***** Found reference cycle *****");
      for (Edge e : cycle) {
        out.println(e.toString());
      }
      out.println("----- Full Types -----");
      for (Edge e : cycle) {
        out.println(e.getOrigin().getSignature());
      }
    }
    out.println();
    out.println(cycles.size() + " CYCLES FOUND.");
  }

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      Options.help(true);
    }
    Options options = Options.parse(args);
    CycleFinder finder = new CycleFinder(options);
    finder.testFileExistence();
    exitOnErrors();
    List<List<Edge>> cycles = finder.findCycles();
    exitOnErrors();
    printCycles(cycles, System.out);
    System.exit(ErrorUtil.errorCount() + cycles.size());
  }
}
