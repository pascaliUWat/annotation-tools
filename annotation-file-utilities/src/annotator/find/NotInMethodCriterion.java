package annotator.find;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;

/**
 * Represents the criterion that a program element is not enclosed by any
 * method (i.e. it's a field, class type parameter, etc.).
 */
final class NotInMethodCriterion implements Criterion {

    /**
     * {@inheritDoc}
     */
    public Kind getKind() {
        return Kind.NOT_IN_METHOD;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isSatisfiedBy(TreePath path) {
        do {
            Tree tree = path.getLeaf();
            if (tree.getKind() == Tree.Kind.METHOD)
                return false;
            if (tree.getKind() == Tree.Kind.CLASS || tree.getKind() == Tree.Kind.NEW_CLASS) {
              return true;
            }
            path = path.getParentPath();
        } while (path != null && path.getLeaf() != null);

        return true;
    }

    /**
     * {@inheritDoc}
     */
    public String toString() {
        return "not in method";
    }

}
