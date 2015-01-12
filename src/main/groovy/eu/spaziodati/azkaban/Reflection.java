package eu.spaziodati.azkaban;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Created by scaiella on 12/01/15.
 */
public class Reflection {
    public static <T> T get(Class cc, String field) {
        return get(cc, null, field);
    }

    public static <T> T get(Object obj, String field) {
        return get(obj.getClass(), obj, field);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Class<?> clazz, Object instance, String field) {
        try {
            Field f = clazz.getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(instance);
        } catch (Exception e ){
            throw new RuntimeException(e);
        }
    }
    
    public static void set(Class<?> clazz, Object instance, String field, Object value) {
        try {
            Field f = clazz.getDeclaredField(field);
            f.setAccessible(true);

            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, f.getModifiers() & ~Modifier.FINAL);

            f.set(instance, value);
            
        } catch (Exception e ) {
            throw new RuntimeException(e);
        }
    }
}
