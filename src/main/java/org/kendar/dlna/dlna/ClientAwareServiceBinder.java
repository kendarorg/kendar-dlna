package org.kendar.dlna.dlna;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import org.jupnp.binding.LocalServiceBindingException;
import org.jupnp.binding.annotations.AnnotationActionBinder;
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder;
import org.jupnp.model.action.ActionExecutor;
import org.jupnp.model.meta.Action;
import org.jupnp.model.meta.StateVariable;
import org.jupnp.model.state.GetterStateVariableAccessor;
import org.jupnp.model.state.StateVariableAccessor;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.model.profile.RemoteClientInfo;

/**
 * Binds the ContentDirectory Browse action to {@link LibraryContentDirectory}'s variant that takes the
 * {@link RemoteClientInfo}. The stock binder walks the class hierarchy and the annotated Browse of the jUPnP base
 * class would otherwise be bound as well (actions compare by identity, so both would be kept); only ours is
 * kept.
 */
public final class ClientAwareServiceBinder extends AnnotationLocalServiceBinder {

    /**
     * The evented {@code SystemUpdateID} must be read through {@link LibraryContentDirectory#getSystemUpdateID()}
     * (the catalog snapshot version): jUPnP binds the annotated private field of its base class by default, which
     * is never updated and would send 0 in every GENA event.
     */
    @Override
    protected Map<StateVariable, StateVariableAccessor> readStateVariables(Class<?> clazz,
            Set<Class> stringConvertibleTypes) throws LocalServiceBindingException {
        Map<StateVariable, StateVariableAccessor> variables = super.readStateVariables(clazz, stringConvertibleTypes);
        if (LibraryContentDirectory.class.isAssignableFrom(clazz)) {
            try {
                Method getter = LibraryContentDirectory.class.getMethod("getSystemUpdateID");
                variables.replaceAll((variable, accessor) -> variable.getName().equals("SystemUpdateID")
                        ? new GetterStateVariableAccessor(getter) : accessor);
            } catch (NoSuchMethodException e) {
                throw new LocalServiceBindingException("getSystemUpdateID not found", e);
            }
        }
        return variables;
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected Map<Action, ActionExecutor> readActions(Class<?> clazz,
            Map<StateVariable, StateVariableAccessor> stateVariables, Set<Class> stringConvertibleTypes)
            throws LocalServiceBindingException {
        Map<Action, ActionExecutor> actions = super.readActions(clazz, stateVariables, stringConvertibleTypes);
        if (LibraryContentDirectory.class.isAssignableFrom(clazz)) {
            // actions compare by identity: drop the inherited ones, bind ours
            actions.keySet().removeIf(action -> action.getName().equals("Browse") || action.getName().equals("Search"));
            try {
                Method browse = LibraryContentDirectory.class.getMethod("browse", String.class, String.class,
                        String.class, UnsignedIntegerFourBytes.class, UnsignedIntegerFourBytes.class, String.class,
                        RemoteClientInfo.class);
                new AnnotationActionBinder(browse, stateVariables, stringConvertibleTypes).appendAction(actions);
                Method search = LibraryContentDirectory.class.getMethod("search", String.class, String.class,
                        String.class, UnsignedIntegerFourBytes.class, UnsignedIntegerFourBytes.class, String.class,
                        RemoteClientInfo.class);
                new AnnotationActionBinder(search, stateVariables, stringConvertibleTypes).appendAction(actions);
            } catch (NoSuchMethodException e) {
                throw new LocalServiceBindingException("Browse or Search with client info not found", e);
            }
        }
        return actions;
    }
}
