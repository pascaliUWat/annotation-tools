package annotator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import plume.FileIOException;
import plume.Option;
import plume.Options;
import plume.Pair;
import plume.UtilMDE;
import type.Type;
import annotations.Annotation;
import annotations.el.ABlock;
import annotations.el.AClass;
import annotations.el.ADeclaration;
import annotations.el.AElement;
import annotations.el.AExpression;
import annotations.el.AField;
import annotations.el.AMethod;
import annotations.el.AScene;
import annotations.el.ATypeElement;
import annotations.el.ATypeElementWithType;
import annotations.el.AnnotationDef;
import annotations.el.DefException;
import annotations.el.ElementVisitor;
import annotations.el.LocalLocation;
import annotations.io.ASTIndex;
import annotations.io.ASTPath;
import annotations.io.IndexFileParser;
import annotations.io.IndexFileWriter;
import annotations.util.coll.VivifyingMap;
import annotator.find.CastInsertion;
import annotator.find.ConstructorInsertion;
import annotator.find.Criteria;
import annotator.find.GenericArrayLocationCriterion;
import annotator.find.Insertion;
import annotator.find.Insertions;
import annotator.find.NewInsertion;
import annotator.find.ReceiverInsertion;
import annotator.find.TreeFinder;
import annotator.find.TypedInsertion;
import annotator.scanner.LocalVariableScanner;
import annotator.specification.IndexFileSpecification;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.TypeAnnotationPosition.TypePathEntry;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.main.CommandLine;
import com.sun.tools.javac.tree.JCTree;

/**
 * This is the main class for the annotator, which inserts annotations in
 * Java source code.  You can call it as <tt>java annotator.Main</tt> or by
 * using the shell script <tt>insert-annotations-to-source</tt>.
 * <p>
 *
 * It takes as input
 * <ul>
 *   <li>annotation (index) files, which indicate the annotations to insert</li>
 *   <li>Java source files, into which the annotator inserts annotations</li>
 * </ul>
 * Use the --help option for full usage details.
 * <p>
 *
 * Annotations that are not for the specified Java files are ignored.
 */
public class Main {

  /** Directory in which output files are written. */
  @Option("-d <directory> Directory in which output files are written")
  public static String outdir = "annotated/";

  /**
   * If true, overwrite original source files (making a backup first).
   * Furthermore, if the backup files already exist, they are used instead
   * of the .java files.  This behavior permits a user to tweak the .jaif
   * file and re-run the annotator.
   * <p>
   *
   * Note that if the user runs the annotator with --in-place, makes edits,
   * and then re-runs the annotator with this --in-place option, those
   * edits are lost.  Similarly, if the user runs the annotator twice in a
   * row with --in-place, only the last set of annotations will appear in
   * the codebase at the end.
   * <p>
   *
   * To preserve changes when using the --in-place option, first remove the
   * backup files.  Or, use the <tt>-d .</tt> option, which makes (and
   * reads) no backup, instead of --in-place.
   */
  @Option("-i Overwrite original source files")
  public static boolean in_place = false;

  @Option("-a Abbreviate annotation names")
  public static boolean abbreviate = true;

  @Option("-c Insert annotations in comments")
  public static boolean comments = false;

  @Option("-o Omit given annotation")
  public static String omit_annotation;

  @Option("-h Print usage information and exit")
  public static boolean help = false;

  @Option("-v Verbose (print progress information)")
  public static boolean verbose;

  @Option("Debug (print debug information)")
  public static boolean debug = false;

  @Option("Print error stack")
  public static boolean print_error_stack = false;

  @Option("Convert JAIFs to new format")
  public static boolean convert_jaifs = false;

  private static ElementVisitor<Void, AElement> classFilter =
      new ElementVisitor<Void, AElement>() {
    <K, V extends AElement>
    Void filter(VivifyingMap<K, V> vm0, VivifyingMap<K, V> vm1) {
      for (Map.Entry<K, V> entry : vm0.entrySet()) {
        entry.getValue().accept(this, vm1.vivify(entry.getKey()));
      }
      return null;
    }

    @Override
    public Void visitAnnotationDef(AnnotationDef def, AElement el) {
      // not used, since package declarations not handled here
      return null;
    }

    @Override
    public Void visitBlock(ABlock el0, AElement el) {
      ABlock el1 = (ABlock) el;
      filter(el0.locals, el1.locals);
      return visitExpression(el0, el);
    }

    @Override
    public Void visitClass(AClass el0, AElement el) {
      AClass el1 = (AClass) el;
      filter(el0.methods, el1.methods);
      filter(el0.fields, el1.fields);
      filter(el0.fieldInits, el1.fieldInits);
      filter(el0.staticInits, el1.staticInits);
      filter(el0.instanceInits, el1.instanceInits);
      return visitDeclaration(el0, el);
    }

    @Override
    public Void visitDeclaration(ADeclaration el0, AElement el) {
      ADeclaration el1 = (ADeclaration) el;
      VivifyingMap<ASTPath, ATypeElement> insertAnnotations =
          el1.insertAnnotations;
      VivifyingMap<ASTPath, ATypeElementWithType> insertTypecasts =
          el1.insertTypecasts;
      for (Map.Entry<ASTPath, ATypeElement> entry :
          el0.insertAnnotations.entrySet()) {
        ASTPath p = entry.getKey();
        ATypeElement e = entry.getValue();
        insertAnnotations.put(p, e);
        //visitTypeElement(e, insertAnnotations.vivify(p));
      }
      for (Map.Entry<ASTPath, ATypeElementWithType> entry :
          el0.insertTypecasts.entrySet()) {
        ASTPath p = entry.getKey();
        ATypeElementWithType e = entry.getValue();
        type.Type type = e.getType();
        if (type instanceof type.DeclaredType
            && ((type.DeclaredType) type).getName().isEmpty()) {
          insertAnnotations.put(p, e);
          //visitTypeElement(e, insertAnnotations.vivify(p));
        } else {
          insertTypecasts.put(p, e);
          //visitTypeElementWithType(e, insertTypecasts.vivify(p));
        }
      }
      return null;
    }

    @Override
    public Void visitExpression(AExpression el0, AElement el) {
      AExpression el1 = (AExpression) el;
      filter(el0.typecasts, el1.typecasts);
      filter(el0.instanceofs, el1.instanceofs);
      filter(el0.news, el1.news);
      return null;
    }

    @Override
    public Void visitField(AField el0, AElement el) {
      return visitDeclaration(el0, el);
    }

    @Override
    public Void visitMethod(AMethod el0, AElement el) {
      AMethod el1 = (AMethod) el;
      filter(el0.bounds, el1.bounds);
      filter(el0.parameters, el1.parameters);
      filter(el0.throwsException, el1.throwsException);
      el0.returnType.accept(this, el1.returnType);
      el0.receiver.accept(this, el1.receiver);
      el0.body.accept(this, el1.body);
      return visitDeclaration(el0, el);
    }

    @Override
    public Void visitTypeElement(ATypeElement el0, AElement el) {
      ATypeElement el1 = (ATypeElement) el;
      filter(el0.innerTypes, el1.innerTypes);
      return null;
    }

    @Override
    public Void visitTypeElementWithType(ATypeElementWithType el0,
        AElement el) {
      ATypeElementWithType el1 = (ATypeElementWithType) el;
      el1.setType(el0.getType());
      return visitTypeElement(el0, el);
    }

    @Override
    public Void visitElement(AElement el, AElement arg) {
      return null;
    }
  };

  private static AScene filteredScene(final AScene scene) {
    final AScene filtered = new AScene();
    filtered.packages.putAll(scene.packages);
    filtered.imports.putAll(scene.imports);
    for (Map.Entry<String, AClass> entry : scene.classes.entrySet()) {
      String key = entry.getKey();
      AClass clazz0 = entry.getValue();
      AClass clazz1 = filtered.classes.vivify(key);
      clazz0.accept(classFilter, clazz1);
    }
    filtered.prune();
    return filtered;
  }

  private static Map<String, TypeTag> primTags = new HashMap<String, TypeTag>();
  {
    primTags.put("byte", TypeTag.BYTE);
    primTags.put("char", TypeTag.CHAR);
    primTags.put("short", TypeTag.SHORT);
    primTags.put("long", TypeTag.LONG);
    primTags.put("float", TypeTag.FLOAT);
    primTags.put("int", TypeTag.INT);
    primTags.put("double", TypeTag.DOUBLE);
    primTags.put("boolean", TypeTag.BOOLEAN);
  }

/*
  private static Pair<ASTPath, Pair<TypeTree, TypePathEntry>>
  inner(ASTPath path, TypePathEntry tpe, Iterator<TypePathEntry> iter,
      TypeTree tt) {
    Tree node = tt;
    int n = 0;

    while (node.getKind() == Tree.Kind.MEMBER_SELECT) {
      MemberSelectTree mst = (MemberSelectTree) node;
      ++n;
      node = mst.getExpression();
    }

    while (tpe.tag == TypePathEntryKind.INNER_TYPE && --n >= 0) {
      if (!iter.hasNext()) {
        tpe = null;
        break;
      }
      tpe = iter.next();
    }

    while (--n >= 0) {
      path = path.add(new ASTPath.ASTEntry(Tree.Kind.MEMBER_SELECT,
          ASTPath.EXPRESSION));
    }

    return Pair.of(path, Pair.of((TypeTree) node, tpe));
  }

  private static ASTPath innerASTPath(ASTPath astPath,
      List<TypePathEntry> tpes, Type type) {
    if (tpes.isEmpty()) { return astPath; }
    Iterator<TypePathEntry> iter = tpes.iterator();
    Pair<ASTPath, Pair<TypeTree, TypePathEntry>> tuple =
        inner(astPath, iter.next(), iter, TypeTree.fromType(type));
    TypePathEntry tpe = tuple.b.b;
    Tree node = tuple.b.a;
    astPath = tuple.a;

    while (tpe != null) {
      Tree.Kind kind = node.getKind();
      switch (kind) {
      case ARRAY_TYPE:
        if (tpe.tag != TypePathEntryKind.ARRAY) { return null; }
        node = ((ArrayTypeTree) node);
        astPath = astPath.add(new ASTPath.ASTEntry(kind, ASTPath.TYPE));
        tpe = iter.hasNext() ? iter.next() : null;
        break;
      case MEMBER_SELECT:
        if (tpe.tag != TypePathEntryKind.INNER_TYPE) { return null; }
        tuple = inner(astPath, tpe, iter, (TypeTree) node);
        tpe = tuple.b.b; node = tuple.b.a; astPath = tuple.a;
        break;
      case PARAMETERIZED_TYPE:
        ParameterizedTypeTree ptt = (ParameterizedTypeTree) node;
        tuple = inner(astPath, tpe, iter, (TypeTree) ptt.getType());
        tpe = tuple.b.b; node = tuple.b.a; astPath = tuple.a;
        if (node.getKind() == Tree.Kind.MEMBER_SELECT
            || tpe.tag != TypePathEntryKind.TYPE_ARGUMENT
            || tpe.arg >= ptt.getTypeArguments().size()) {
          return null;
        }
        node = ptt.getTypeArguments().get(tpe.arg);
        astPath = astPath.add(new ASTPath.ASTEntry(kind,
                ASTPath.TYPE_PARAMETER, tpe.arg));
        tpe = iter.hasNext() ? iter.next() : null;
        break;
      case EXTENDS_WILDCARD:
      case SUPER_WILDCARD:
        if (tpe.tag != TypePathEntryKind.WILDCARD) { return null; }
        node = ((WildcardTree) node).getBound();
        astPath = astPath.add(  // ASTPath uses UNBOUNDED for all wildcards
            new ASTPath.ASTEntry(Tree.Kind.UNBOUNDED_WILDCARD, ASTPath.BOUND));
        tpe = iter.hasNext() ? iter.next() : null;
        break;
      default:
        return null;
      }

      if (node == null) { return null; }
    }
    return astPath;
  }
*/

  private static ATypeElement findInnerTypeElement(Tree t,
      ASTIndex.ASTRecord rec, ADeclaration decl, Type type, Insertion ins) {
    ASTPath astPath = rec.astPath;
    GenericArrayLocationCriterion galc =
        ins.getCriteria().getGenericArrayLocation();
    assert astPath != null && galc != null;
    List<TypePathEntry> tpes = galc.getLocation();
    ASTPath.ASTEntry entry;
    for (TypePathEntry tpe : tpes) {
      switch (tpe.tag) {
      case ARRAY:
        if (!astPath.isEmpty()) {
          entry = astPath.get(-1);
          if (entry.getTreeKind() == Tree.Kind.NEW_ARRAY
              && entry.childSelectorIs(ASTPath.TYPE)) {
            entry = new ASTPath.ASTEntry(Tree.Kind.NEW_ARRAY,
                ASTPath.TYPE, entry.getArgument() + 1);
            break;
          }
        }
        entry = new ASTPath.ASTEntry(Tree.Kind.ARRAY_TYPE,
            ASTPath.TYPE);
        break;
      case INNER_TYPE:
        entry = new ASTPath.ASTEntry(Tree.Kind.MEMBER_SELECT,
            ASTPath.EXPRESSION);
        break;
      case TYPE_ARGUMENT:
        entry = new ASTPath.ASTEntry(Tree.Kind.PARAMETERIZED_TYPE,
            ASTPath.TYPE_ARGUMENT, tpe.arg);
        break;
      case WILDCARD:
        entry = new ASTPath.ASTEntry(Tree.Kind.UNBOUNDED_WILDCARD,
            ASTPath.BOUND);
        break;
      default:
        throw new IllegalArgumentException("unknown type tag " + tpe.tag);
      }
      astPath.add(entry);
    }

    return decl.insertAnnotations.vivify(astPath);
  }

  // Implementation details:
  //  1. The annotator partially compiles source
  //     files using the compiler API (JSR-199), obtaining an AST.
  //  2. The annotator reads the specification file, producing a set of
  //     annotator.find.Insertions.  Insertions completely specify what to
  //     write (as a String, which is ultimately translated according to the
  //     keyword file) and how to write it (as annotator.find.Criteria).
  //  3. It then traverses the tree, looking for nodes that satisfy the
  //     Insertion Criteria, translating the Insertion text against the
  //     keyword file, and inserting the annotations into the source file.

  /**
   * Runs the annotator, parsing the source and spec files and applying
   * the annotations.
   */
  public static void main(String[] args) throws IOException {

    if (verbose) {
      System.out.printf("insert-annotations-to-source (%s)",
                        annotations.io.classfile.ClassFileReader.INDEX_UTILS_VERSION);
    }

    Options options = new Options(
        "Main [options] { ann-file | java-file | @arg-file } ...\n"
            + "(Contents of argfiles are expanded into the argument list.)",
        Main.class);
    String[] file_args;
    try {
      String[] cl_args = CommandLine.parse(args);
      file_args = options.parse_or_usage(cl_args);
    } catch (IOException ex) {
      System.err.println(ex);
      System.err.println("(For non-argfile beginning with \"@\", use \"@@\" for initial \"@\".");
      System.err.println("Alternative for filenames: indicate directory, e.g. as './@file'.");
      System.err.println("Alternative for flags: use '=', as in '-o=@Deprecated'.)");
      file_args = null;  // Eclipse compiler issue workaround
      System.exit(1);
    }

    if (debug) {
      TreeFinder.debug = true;
      Criteria.debug = true;
    }

    if (convert_jaifs) {
      TreeFinder.convert_jaifs = true;
    }

    if (help) {
      options.print_usage();
      System.exit(0);
    }

    if (in_place && outdir != "annotated/") { // interned
      options.print_usage("The --outdir and --in-place options are mutually exclusive.");
      System.exit(1);
    }

    if (file_args.length < 2) {
      options.print_usage("Supplied %d arguments, at least 2 needed%n", file_args.length);
      System.exit(1);
    }

    // The insertions specified by the annotation files.
    Insertions insertions = new Insertions();
    // The Java files into which to insert.
    List<String> javafiles = new ArrayList<String>();

    Set<String> imports = new LinkedHashSet<String>();
    Map<String, Multimap<Insertion, Annotation>> insertionIndex =
        new HashMap<String, Multimap<Insertion, Annotation>>();
    Map<Insertion, String> insertionOrigins = new HashMap<Insertion, String>();
    Map<String, AScene> scenes = new HashMap<String, AScene>();

    IndexFileParser.setAbbreviate(abbreviate);
    for (String arg : file_args) {
      if (arg.endsWith(".java")) {
        javafiles.add(arg);
      } else if (arg.endsWith(".jaif") ||
                 arg.endsWith(".jann")) {
        IndexFileSpecification spec = new IndexFileSpecification(arg);
        try {
          List<Insertion> parsedSpec = spec.parse();
          AScene scene = spec.getScene();
          parsedSpec.sort(new Comparator<Insertion>() {
            @Override
            public int compare(Insertion i1, Insertion i2) {
              ASTPath p1 = i1.getCriteria().getASTPath();
              ASTPath p2 = i2.getCriteria().getASTPath();
              return p1 == null
                  ? p2 == null ? 0 : -1
                  : p2 == null ? 1 : p1.compareTo(p2);
            }
          });
          scenes.put(arg, convert_jaifs ? filteredScene(scene) : scene);
          for (Insertion ins : parsedSpec) {
            insertionOrigins.put(ins, arg);
          }
          if (!insertionIndex.containsKey(arg)) {
            insertionIndex.put(arg,
                LinkedHashMultimap.<Insertion, Annotation>create());
          }
          insertionIndex.get(arg).putAll(spec.insertionSources());
          if (abbreviate) {
            Map<String, Set<String>> annotationImports =
                spec.annotationImports();
            for (Set<String> set : annotationImports.values()) {
              imports.addAll(set);
            }
          }
          if (verbose || debug) {
            System.out.printf("Read %d annotations from %s%n",
                              parsedSpec.size(), arg);
          }
          if (omit_annotation != null) {
            List<Insertion> filtered = new ArrayList<Insertion>(parsedSpec.size());
            for (Insertion insertion : parsedSpec) {
              // TODO: this won't omit annotations if the insertion is more than
              // just the annotation (such as if the insertion is a cast
              // insertion or a 'this' parameter in a method declaration).
              if (! omit_annotation.equals(insertion.getText())) {
                filtered.add(insertion);
              }
            }
            parsedSpec = filtered;
            if (verbose || debug) {
              System.out.printf("After filtering: %d annotations from %s%n",
                                parsedSpec.size(), arg);
            }
          }
          insertions.addAll(parsedSpec);
        } catch (RuntimeException e) {
          if (e.getCause() != null
              && e.getCause() instanceof FileNotFoundException) {
            System.err.println("File not found: " + arg);
            System.exit(1);
          } else {
            throw e;
          }
        } catch (FileIOException e) {
          // Add 1 to the line number since line numbers in text editors are usually one-based.
          System.err.println("Error while parsing annotation file " + arg + " at line "
              + (e.lineNumber + 1) + ":");
          if (e.getMessage() != null) {
            System.err.println('\t' + e.getMessage());
          }
          if (e.getCause() != null && e.getCause().getMessage() != null) {
            System.err.println('\t' + e.getCause().getMessage());
          }
          if (debug) {
            e.printStackTrace();
          }
          System.exit(1);
        }
      } else {
        throw new Error("Unrecognized file extension: " + arg);
      }
    }

    if (debug) {
      System.out.printf("%d insertions, %d .java files%n", insertions.size(), javafiles.size());
    }
    if (debug) {
      System.out.printf("Insertions:%n");
      for (Insertion insertion : insertions) {
        System.out.printf("  %s%n", insertion);
      }
    }

    for (String javafilename : javafiles) {
      if (verbose) {
        System.out.println("Processing " + javafilename);
      }

      File javafile = new File(javafilename);
      File unannotated = new File(javafilename + ".unannotated");
      if (in_place) {
        // It doesn't make sense to check timestamps;
        // if the .java.unannotated file exists, then just use it.
        // A user can rename that file back to just .java to cause the
        // .java file to be read.
        if (unannotated.exists()) {
          if (verbose) {
            System.out.printf("Renaming %s to %s%n", unannotated, javafile);
          }
          boolean success = unannotated.renameTo(javafile);
          if (! success) {
            throw new Error(String.format("Failed renaming %s to %s",
                                          unannotated, javafile));
          }
        }
      }

      String fileSep = System.getProperty("file.separator");
      String fileLineSep = System.getProperty("line.separator");
      Source src;
      // Get the source file, and use it to obtain parse trees.
      try {
        // fileLineSep is set here so that exceptions can be caught
        fileLineSep = UtilMDE.inferLineSeparator(javafilename);
        src = new Source(javafilename);
        if (verbose) {
          System.out.printf("Parsed %s%n", javafilename);
        }
      } catch (Source.CompilerException e) {
        e.printStackTrace();
        return;
      } catch (IOException e) {
        e.printStackTrace();
        return;
      }

      int num_insertions = 0;
      String pkg = "";

      for (CompilationUnitTree cut : src.parse()) {
        JCTree.JCCompilationUnit tree = (JCTree.JCCompilationUnit) cut;
        ExpressionTree pkgExp = cut.getPackageName();
        pkg = pkgExp == null ? "" : pkgExp.toString();

        // Create a finder, and use it to get positions.
        TreeFinder finder = new TreeFinder(tree);
        if (debug) {
          TreeFinder.debug = true;
        }
        SetMultimap<Integer, Insertion> positions = finder.getPositions(tree, insertions);

        if (convert_jaifs) {
          // program used only for JAIF conversion; execute following
          // block and then skip remainder of loop 
          Multimap<ASTIndex.ASTRecord, Insertion> astInsertions =
              finder.getPaths();
          for (Map.Entry<ASTIndex.ASTRecord, Collection<Insertion>> entry :
              astInsertions.asMap().entrySet()) {
            ASTIndex.ASTRecord rec = entry.getKey();
            for (Insertion ins : entry.getValue()) {
              if (ins.getCriteria().getASTPath() != null) { continue; }
              String arg = insertionOrigins.get(ins);
              AScene scene = scenes.get(arg);
              Multimap<Insertion, Annotation> insertionSources =
                  insertionIndex.get(arg);
              //String text =
              //  ins.getText(comments, abbreviate, false, 0, '\0');

              // TODO: adjust for missing end of path (?)

              if (insertionSources.containsKey(ins)) {
                if (rec == null) {
                  if (ins.getCriteria().isOnPackage()) {
                    for (Annotation anno : insertionSources.get(ins)) {
                      scene.packages.get(pkg).tlAnnotationsHere.add(anno);
                    }
                  }
                } else if (scene != null && rec.className != null) {
                  AClass clazz = scene.classes.vivify(rec.className);
                  ADeclaration decl = null;  // insertion target
                  if (ins.getCriteria().onBoundZero()) {
                    int n = rec.astPath.size();
                    if (!rec.astPath.get(n-1).childSelectorIs(ASTPath.BOUND)) {
                      ASTPath astPath = new ASTPath();
                      for (int i = 0; i < n; i++) {
                        astPath.add(rec.astPath.get(i));
                      }
                      astPath.add(
                          new ASTPath.ASTEntry(Tree.Kind.TYPE_PARAMETER,
                              ASTPath.BOUND, 0));
                      rec = rec.replacePath(astPath);
                    }
                  }
                  if (rec.methodName == null) {
                    decl = rec.varName == null ? clazz
                        : clazz.fields.vivify(rec.varName);
                  } else {
                    AMethod meth = clazz.methods.vivify(rec.methodName);
                    if (rec.varName == null) {
                      decl = meth;  // ?
                    } else {
                      try {
                        int i = Integer.parseInt(rec.varName);
                        decl = i < 0 ? meth.receiver
                            : meth.parameters.vivify(i);
                      } catch (NumberFormatException e) {
                        TreePath path = ASTIndex.getTreePath(tree, rec);
                        JCTree.JCVariableDecl varTree = null;
                        JCTree.JCMethodDecl methTree = null;
                        JCTree.JCClassDecl classTree = null;
loop:
                        while (path != null) {
                          Tree leaf = path.getLeaf();
                          switch (leaf.getKind()) {
                          case VARIABLE:
                            varTree = (JCTree.JCVariableDecl) leaf;
                            break;
                          case METHOD:
                            methTree = (JCTree.JCMethodDecl) leaf;
                            break;
                          case ANNOTATION:
                          case CLASS:
                          case ENUM:
                          case INTERFACE:
                            break loop;
                          default:
                            path = path.getParentPath();
                          }
                        }
                        while (path != null) {
                          Tree leaf = path.getLeaf();
                          Tree.Kind kind = leaf.getKind();
                          if (kind == Tree.Kind.METHOD) {
                            methTree = (JCTree.JCMethodDecl) leaf;
                            int i = LocalVariableScanner.indexOfVarTree(path,
                                varTree, rec.varName);
                            int m = methTree.getStartPosition();
                            int a = varTree.getStartPosition();
                            int b = varTree.getEndPosition(tree.endPositions);
                            LocalLocation loc = new LocalLocation(i, a-m, b-a);
                            decl = meth.body.locals.vivify(loc);
                            break;
                          }
                          if (ASTPath.isClassEquiv(kind)) {
                            classTree = (JCTree.JCClassDecl) leaf;
                            // ???
                            break;
                          }
                          path = path.getParentPath();
                        }
                      }
                    }
                  }
                  if (decl != null) {
                    AElement el;
                    if (rec.astPath.isEmpty()) {
                      el = decl;
                    } else if (ins.getKind() == Insertion.Kind.CAST) {
                      annotations.el.ATypeElementWithType elem =
                          decl.insertTypecasts.vivify(rec.astPath);
                      elem.setType(((CastInsertion) ins).getType());
                      el = elem;
                    } else {
                      el = decl.insertAnnotations.vivify(rec.astPath);
                    }
                    for (Annotation anno : insertionSources.get(ins)) {
                      el.tlAnnotationsHere.add(anno);
                    }
                    if (ins instanceof TypedInsertion) {
                      TypedInsertion ti = (TypedInsertion) ins;
//                      Type type = ti.getType();
//                      ASTPath astPath = rec.astPath;
//                      if (!astPath.isEmpty()) {
//                        ASTPath.ASTEntry lastEntry = astPath.get(-1);
//                        if (lastEntry.getTreeKind() == Tree.Kind.METHOD
//                            && lastEntry.childSelectorIs(ASTPath.PARAMETER)) {
//                          astPath = astPath.add(
//                              new ASTPath.ASTEntry(Tree.Kind.VARIABLE,
//                                  ASTPath.TYPE));
//                        }
//                      }
//                      VivifyingMap<ASTPath, ? extends ATypeElement> vm =
//                          ti.getKind() == Insertion.Kind.CAST
//                              ? decl.insertTypecasts
//                              : decl.insertAnnotations;
                      for (Insertion inner : ti.getInnerTypeInsertions()) {
                        Tree t = ASTIndex.getNode(cut, rec);
                        if (t != null) {
                          ATypeElement elem = findInnerTypeElement(t,
                              rec, decl, ti.getType(), inner);
                          for (Annotation a : insertionSources.get(inner)) {
                            elem.tlAnnotationsHere.add(a);
                          }
//                        GenericArrayLocationCriterion galc =
//                            inner.getCriteria().getGenericArrayLocation();
//                        ASTPath path =
//                            innerASTPath(astPath, galc.getLocation(), type);
//                        ATypeElement elem = vm.vivify(path);
//                        for (Annotation a : insertionSources.get(inner)) {
//                          elem.tlAnnotationsHere.add(a);
                        }
                      }
                    }
                  }
                }
              }
            }
          }
          continue;
        }

        // Apply the positions to the source file.
        if (debug || verbose) {
          System.err.printf("getPositions returned %d positions in tree for %s%n", positions.size(), javafilename);
        }

        Set<Integer> positionKeysUnsorted = positions.keySet();
        Set<Integer> positionKeysSorted =
          new TreeSet<Integer>(new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
              return o1.compareTo(o2) * -1;
            }
          });
        positionKeysSorted.addAll(positionKeysUnsorted);
        for (Integer pos : positionKeysSorted) {
          boolean receiverInserted = false;
          boolean newInserted = false;
          boolean constructorInserted = false;
          List<Insertion> toInsertList = new ArrayList<Insertion>(positions.get(pos));
          Collections.reverse(toInsertList);
          if (debug) {
            System.out.printf("insertion pos: %d%n", pos);
          }
          assert pos >= 0
            : "pos is negative: " + pos + " " + toInsertList.get(0) + " " + javafilename;
          for (Insertion iToInsert : toInsertList) {
            // Possibly add whitespace after the insertion
            String trailingWhitespace = "";
            boolean gotSeparateLine = false;
            if (iToInsert.getSeparateLine()) {
              // System.out.printf("getSeparateLine=true for insertion at pos %d: %s%n", pos, iToInsert);
              int indentation = 0;
              while ((pos - indentation != 0)
                     // horizontal whitespace
                     && (src.charAt(pos-indentation-1) == ' '
                         || src.charAt(pos-indentation-1) == '\t')) {
                // System.out.printf("src.charAt(pos-indentation-1 == %d-%d-1)='%s'%n",
                //                   pos, indentation, src.charAt(pos-indentation-1));
                indentation++;
              }
              if ((pos - indentation == 0)
                  // horizontal whitespace
                  || (src.charAt(pos-indentation-1) == '\f'
                      || src.charAt(pos-indentation-1) == '\n'
                      || src.charAt(pos-indentation-1) == '\r')) {
                trailingWhitespace = fileLineSep + src.substring(pos-indentation, pos);
                gotSeparateLine = true;
              }
            }

            char precedingChar;
            if (pos != 0) {
              precedingChar = src.charAt(pos - 1);
            } else {
              precedingChar = '\0';
            }
            if (iToInsert.getKind() == Insertion.Kind.CAST) {
                ((CastInsertion) iToInsert)
                        .setOnArrayLiteral(src.charAt(pos) == '{');
            }

            if (iToInsert.getKind() == Insertion.Kind.RECEIVER) {
              ReceiverInsertion ri = (ReceiverInsertion) iToInsert;
              ri.setAnnotationsOnly(receiverInserted);
              receiverInserted = true;
            } else if (iToInsert.getKind() == Insertion.Kind.NEW) {
              NewInsertion ni = (NewInsertion) iToInsert;
              ni.setAnnotationsOnly(newInserted);
              newInserted = true;
            } else if (iToInsert.getKind() == Insertion.Kind.CONSTRUCTOR) {
              ConstructorInsertion ci = (ConstructorInsertion) iToInsert;
              if (constructorInserted) { ci.setAnnotationsOnly(true); }
              constructorInserted = true;
            }

            String toInsert = iToInsert.getText(comments, abbreviate,
                    gotSeparateLine, pos, precedingChar) + trailingWhitespace;
            if (abbreviate) {
              Set<String> packageNames = iToInsert.getPackageNames();
              if (debug) {
                System.out.printf("Need import %s%n  due to insertion %s%n",
                                  packageNames, toInsert);
              }
              imports.addAll(packageNames);
            }

            // If it's already there, don't re-insert.  This is a hack!
            // Also, I think this is already checked when constructing the
            // insertions.
            int precedingTextPos = pos-toInsert.length()-1;
            if (precedingTextPos >= 0) {
              String precedingTextPlusChar
                = src.getString().substring(precedingTextPos, pos);
              // System.out.println("Inserting " + toInsert + " at " + pos + " in code of length " + src.getString().length() + " with preceding text '" + precedingTextPlusChar + "'");
              if (toInsert.equals(precedingTextPlusChar.substring(0, toInsert.length()))
                  || toInsert.equals(precedingTextPlusChar.substring(1))) {
                if (debug) {
                    System.out.println("Inserting " + toInsert + " at " + pos + " in code of length " + src.getString().length() + " with preceding text '" + precedingTextPlusChar + "'");
                    System.out.println("Already present, skipping");
                }
                continue;
              }
            }

            // TODO: Neither the above hack nor this check should be
            // necessary.  Find out why re-insertions still occur and
            // fix properly.
            if (iToInsert.getInserted()) { continue; }
            src.insert(pos, toInsert);
            if (verbose) {
              System.out.print(".");
              num_insertions++;
              if ((num_insertions % 50) == 0) {
                System.out.println();   // terminate the line that contains dots
              }
            }
            if (debug) {
              System.out.println("Post-insertion source: " + src.getString());
            }
          }
        }
      }

      if (convert_jaifs) {
        for (Map.Entry<String, AScene> entry : scenes.entrySet()) {
          String filename = entry.getKey();
          AScene scene = entry.getValue();
          try {
            IndexFileWriter.write(scene, filename + ".converted");
          } catch (DefException e) {
            System.err.println(filename + ": " + " format error in conversion");
            if (print_error_stack) {
              e.printStackTrace();
            }
          }
        }
        return;  // done with conversion
      }

      if (verbose) {
        if ((num_insertions % 50) != 0) {
          System.out.println();   // terminate the line that contains dots
        }
      }

      if (debug) {
        System.out.println(imports.size() + " imports to insert");
        for (String classname : imports) {
          System.out.println("  " + classname);
        }
      }

      // insert import statements
      {
        Pattern importPattern = Pattern.compile("(?m)^import\\b");
        Pattern packagePattern = Pattern.compile("(?m)^package\\b.*;(\\n|\\r\\n?)");
        int importIndex = 0;      // default: beginning of file
        String srcString = src.getString();
        Matcher m = importPattern.matcher(srcString);
        if (m.find()) {
          importIndex = m.start();
        } else {
          // if (debug) {
          //   System.out.println("Didn't find import in " + srcString);
          // }
          m = packagePattern.matcher(srcString);
          if (m.find()) {
            importIndex = m.end();
          }
        }
        for (String classname : imports) {
          String toInsert = "import " + classname + ";" + fileLineSep;
          src.insert(importIndex, toInsert);
          importIndex += toInsert.length();
        }
      }

      // Write the source file.
      File outfile = null;
      try {
        if (in_place) {
          outfile = javafile;
          if (verbose) {
            System.out.printf("Renaming %s to %s%n", javafile, unannotated);
          }
          boolean success = javafile.renameTo(unannotated);
          if (! success) {
            throw new Error(String.format("Failed renaming %s to %s",
                                          javafile, unannotated));
          }
        } else {
          if (pkg.isEmpty()) {
            outfile = new File(outdir, javafile.getName());
          } else {
            String[] pkgPath = pkg.split("\\.");
            StringBuilder sb = new StringBuilder(outdir);
            for (int i = 0 ; i < pkgPath.length ; i++) {
              sb.append(fileSep).append(pkgPath[i]);
            }
            outfile = new File(sb.toString(), javafile.getName());
          }
          outfile.getParentFile().mkdirs();
        }
        OutputStream output = new FileOutputStream(outfile);
        if (verbose) {
          System.out.printf("Writing %s%n", outfile);
        }
        src.write(output);
        output.close();
      } catch (IOException e) {
        System.err.println("Problem while writing file " + outfile);
        e.printStackTrace();
        System.exit(1);
      }
    }
  }

  ///
  /// Utility methods
  ///

  public static String pathToString(TreePath path) {
    if (path == null)
      return "null";
    return treeToString(path.getLeaf());
  }

  public static String treeToString(Tree node) {
    String asString = node.toString();
    String oneLine = firstLine(asString);
    return "\"" + oneLine + "\"";
  }

  /**
   * Return the first non-empty line of the string, adding an ellipsis
   * (...) if the string was truncated.
   */
  public static String firstLine(String s) {
    while (s.startsWith("\n")) {
      s = s.substring(1);
    }
    int newlineIndex = s.indexOf('\n');
    if (newlineIndex == -1) {
      return s;
    } else {
      return s.substring(0, newlineIndex) + "...";
    }
  }

  /**
   * Separates the annotation class from its arguments.
   *
   * @return given <code>@foo(bar)</code> it returns the pair <code>{ @foo, (bar) }</code>.
   */
  public static Pair<String,String> removeArgs(String s) {
    int pidx = s.indexOf("(");
    return (pidx == -1) ?
        Pair.of(s, (String)null) :
        Pair.of(s.substring(0, pidx), s.substring(pidx));
  }

/*
  private static abstract class TypeTree implements ExpressionTree {
    static TypeTree fromType(final Type type) {
      switch (type.getKind()) {
      case ARRAY:
        final ArrayType atype = (ArrayType) type;
        final TypeTree componentType = fromType(atype.getComponentType());
        return new ArrT(componentType);
      case BOUNDED:
        final BoundedType btype = (BoundedType) type;
        final BoundedType.BoundKind bk = btype.getBoundKind();
        final String bname = btype.getType().getName();
        final TypeTree bound = fromType(btype.getBound());
        return new Param(bname, bk, bound);
      case DECLARED:
        final DeclaredType dtype = (DeclaredType) type;
        if (dtype.isWildcard()) {
          return new WildT();
        } else {
          final String dname = dtype.getName();
          TypeTag typeTag = primTags.get(dname);
          if (typeTag == null) {
            final TypeTree base = new IdenT(dname);
            TypeTree ret = base;
            List<Type> params = dtype.getTypeParameters();
            DeclaredType inner = dtype.getInnerType();
            if (!params.isEmpty()) {
              final List<Tree> typeArgs = new ArrayList<Tree>(params.size());
              for (Type t : params) { typeArgs.add(fromType(t)); }
              ret = new ParT(base, typeArgs);
            }
            return inner == null ? ret : meld(fromType(inner), ret);
          } else {
            final TypeKind typeKind = typeTag.getPrimitiveTypeKind();
            return new PrimT(typeKind);
          }
        }
      default:
        throw new RuntimeException("unknown type kind " + type.getKind());
      }
    }
  
    private static TypeTree meld(final TypeTree t0, final TypeTree t1) {
      switch (t0.getKind()) {
      case IDENTIFIER:
        IdenT it = (IdenT) t0;
        return new LocT(t1, it.getName());
      case MEMBER_SELECT:
        LocT lt = (LocT) t0;
        return new LocT(meld(lt.getExpression(), t1), lt.getIdentifier());
      case PARAMETERIZED_TYPE:
        ParT pt = (ParT) t0;
        return new ParT(meld(pt.getType(), t1), pt.getTypeArguments());
      default:
        throw new IllegalArgumentException("unexpected type " + t0);
      }
    }
  
    static final class ArrT extends TypeTree implements ArrayTypeTree {
      private final TypeTree componentType;

      ArrT(TypeTree componentType) {
        this.componentType = componentType;
      }

      @Override
      public Kind getKind() { return Kind.ARRAY_TYPE; }

      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitArrayType(this, data);
      }

      @Override
      public TypeTree getType() { return componentType; }

      @Override
      public String toString() { return componentType + "[]"; }
    }

    static final class LocT extends TypeTree implements MemberSelectTree {
      private final TypeTree expr;
      private final Name name;
    
      LocT(TypeTree expr, Name name) {
        this.expr = expr;
        this.name = name;
      }
    
      @Override
      public Kind getKind() { return Kind.MEMBER_SELECT; }
    
      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitMemberSelect(this, data);
      }
    
      @Override
      public TypeTree getExpression() { return expr; }
    
      @Override
      public Name getIdentifier() { return name; }
    
      @Override
      public String toString() { return expr + "." + name; }
    }

    static final class ParT extends TypeTree implements ParameterizedTypeTree {
      private final TypeTree base;
      private final List<? extends Tree> typeArgs;
    
      ParT(TypeTree base, List<? extends Tree> typeArgs) {
        this.base = base;
        this.typeArgs = typeArgs;
      }
    
      @Override
      public Kind getKind() { return Kind.PARAMETERIZED_TYPE; }
    
      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitParameterizedType(this, data);
      }
    
      @Override
      public TypeTree getType() { return base; }
    
      @Override
      public List<? extends Tree> getTypeArguments() {
        return typeArgs;
      }
    
      @Override
      public String toString() {
        StringBuilder sb = new StringBuilder(base.toString());
        String s = "<";
        for (Tree t : typeArgs) {
          sb.append(s);
          sb.append(t.toString());
          s = ", ";
        }
        sb.append('>');
        return sb.toString();
      }
    }

    static final class PrimT extends TypeTree implements PrimitiveTypeTree {
      private final TypeKind typeKind;
    
      PrimT(TypeKind typeKind) {
        this.typeKind = typeKind;
      }
    
      @Override
      public Kind getKind() { return Kind.PRIMITIVE_TYPE; }
    
      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitPrimitiveType(this, data);
      }
    
      @Override
      public TypeKind getPrimitiveTypeKind() { return typeKind; }

      @Override
      public String toString() {
        switch (typeKind) {
        case BOOLEAN: return "boolean";
        case BYTE: return "byte";
        case CHAR: return "char";
        case DOUBLE: return "double";
        case FLOAT: return "float";
        case INT: return "int";
        case LONG: return "long";
        case SHORT: return "short";
        //case VOID: return "void";
        //case WILDCARD: return "?";
        default:
          throw new IllegalArgumentException("unexpected type kind "
              + typeKind);
        }
      }
    }

    static final class IdenT extends TypeTree implements IdentifierTree {
      private final String name;

      IdenT(String dname) {
        this.name = dname;
      }

      @Override
      public Kind getKind() { return Kind.IDENTIFIER; }

      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitIdentifier(this, data);
      }

      @Override
      public Name getName() { return new TypeName(name); }

      @Override
      public String toString() { return name; }
    }

    static final class WildT extends TypeTree implements WildcardTree {
      @Override
      public Kind getKind() { return Kind.UNBOUNDED_WILDCARD; }

      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitWildcard(this, data);
      }

      @Override
      public Tree getBound() { return null; }

      @Override
      public String toString() { return "?"; }
    }

    static final class Param extends TypeTree implements TypeParameterTree {
      private final String bname;
      private final BoundKind bk;
      private final Tree bound;
    
      Param(String bname, BoundKind bk, TypeTree bound) {
        this.bname = bname;
        this.bk = bk;
        this.bound = bound;
      }
    
      @Override
      public Kind getKind() { return Kind.TYPE_PARAMETER; }
    
      @Override
      public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
        return visitor.visitTypeParameter(this, data);
      }
    
      @Override
      public Name getName() { return new TypeName(bname); }
    
      @Override
      public List<? extends Tree> getBounds() {
        return Collections.singletonList(bound);
      }
    
      @Override
      public List<? extends AnnotationTree> getAnnotations() {
        return Collections.emptyList();
      }
    
      @Override
      public String toString() {
        return bname + " " + bk.toString() + " " + bound.toString();
      }
    }

    static final class TypeName implements Name {
      private final String str;
    
      TypeName(String str) {
        this.str = str;
      }
    
      @Override
      public int length() { return str.length(); }
    
      @Override
      public char charAt(int index) { return str.charAt(index); }
    
      @Override
      public CharSequence subSequence(int start, int end) {
        return str.subSequence(start, end);
      }
    
      @Override
      public boolean contentEquals(CharSequence cs) {
        if (cs != null) {
          int n = length();
          if (cs.length() == n) {
            for (int i = 0; i < n; i++) {
              if (charAt(i) != cs.charAt(i)) { return false; }
            }
            return true;
          }
        }
        return false;
      }
    
      @Override
      public String toString() { return str; }
    }
  }
*/
}
