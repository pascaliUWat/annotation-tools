package scenelib.annotations.field;

/*>>>
import org.checkerframework.checker.nullness.qual.*;
*/

import java.util.Collection;

/**
 * An {@link ArrayAFT} represents an annotation field type that is an array.
 */
public final class ArrayAFT extends AnnotationFieldType {

    /**
     * The element type of the array, or <code>null</code> if it is unknown
     * (see {@link scenelib.annotations.AnnotationBuilder#addEmptyArrayField}).
     */
    public final ScalarAFT elementType;

    /**
     * Constructs a new {@link ArrayAFT} representing an array type with
     * the given element type.  <code>elementType</code> may be
     * <code>null</code> to indicate that the element type is unknown
     * (see {@link scenelib.annotations.AnnotationBuilder#addEmptyArrayField}).
     */
    public ArrayAFT(ScalarAFT elementType) {
        this.elementType = elementType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isValidValue(Object o) {
        if (! (o instanceof Collection)) {
            return false;
        }
        Collection<?> asCollection = (Collection<?>) o;
        if (elementType == null) {
            return (asCollection.size() == 0);
        }
        for (Object elt : asCollection) {
            if (! elementType.isValidValue(elt)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public  String toString() {
        return (elementType == null ? "unknown" :
            ((ScalarAFT) elementType).toString()) + "[]";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String format(Object o) {
        Collection<?> asCollection = (Collection<?>)o;
        StringBuilder result = new StringBuilder();
        result.append("{");
        for (Object elt : asCollection) {
            if (result.length() > 1) {
                result.append(",");
            }
            result.append(elementType.format(elt));
        }
        result.append("}");
        return result.toString();
    }

    @Override
    public <R, T> R accept(AFTVisitor<R, T> v, T arg) {
        return v.visitArrayAFT(this, arg);
    }
}
