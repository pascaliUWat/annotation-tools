package annotator.find;

import annotations.el.BoundLocation;

import com.sun.source.util.TreePath;

public class ClassBoundCriterion implements Criterion {

  private String className;
  private BoundLocation boundLoc;
  private Criterion notInMethodCriterion;
  private Criterion boundLocCriterion;

  public ClassBoundCriterion(String className, BoundLocation boundLoc) {
    this.className = className;
    this.boundLoc = boundLoc;
    this.notInMethodCriterion = Criteria.notInMethod();
    this.boundLocCriterion = Criteria.atBoundLocation(boundLoc);
  }

  public boolean isSatisfiedBy(TreePath path) {
    if (path == null) {
      return false;
    }

    return boundLocCriterion.isSatisfiedBy(path) &&
      notInMethodCriterion.isSatisfiedBy(path);
  }

  public Kind getKind() {
    return Kind.CLASS_BOUND;
  }

  public String toString() {
    return "ClassBoundCriterion: for " + className + " at " + boundLoc;
  }
}
