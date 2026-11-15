package me.jamino.wynndhrangelimiter.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * One member of another mod that is reached by name, because the mod is not compiled against. The support
 * class looks the member up through this, and {@code ReplayModJarsTest} checks the same declaration against
 * the mod's jar, so that an update of the mod that renames or changes it fails a test instead of only
 * logging a warning in the game.
 *
 * @param owner    the class the member is looked up on; a public member may be inherited by it
 * @param name     the member's name
 * @param kind     how it is looked up
 * @param isStatic whether it is read or called without an instance
 * @param type     the class name of the field's type or of the method's return type as the support class
 *                 relies on it; null when any type will do
 */
public record Reflected(String owner, String name, Kind kind, boolean isStatic, String type) {
    public enum Kind {
        /** A public field, by {@link Class#getField}. */
        FIELD,
        /** A field of any visibility declared by the owner itself, by {@link Class#getDeclaredField}. */
        DECLARED_FIELD,
        /** A public method without parameters, by {@link Class#getMethod}. */
        METHOD
    }

    public static Reflected field(String owner, String name, String type) {
        return new Reflected(owner, name, Kind.FIELD, false, type);
    }

    public static Reflected staticField(String owner, String name, String type) {
        return new Reflected(owner, name, Kind.FIELD, true, type);
    }

    public static Reflected declaredField(String owner, String name, String type) {
        return new Reflected(owner, name, Kind.DECLARED_FIELD, false, type);
    }

    public static Reflected method(String owner, String name, String type) {
        return new Reflected(owner, name, Kind.METHOD, false, type);
    }

    public static Reflected staticMethod(String owner, String name, String type) {
        return new Reflected(owner, name, Kind.METHOD, true, type);
    }

    public Field asField() throws ReflectiveOperationException {
        Class<?> c = Class.forName(owner);
        if (kind == Kind.FIELD) return c.getField(name);
        Field field = c.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public Method asMethod() throws ReflectiveOperationException {
        return Class.forName(owner).getMethod(name);
    }

    @Override
    public String toString() {
        return owner + "." + name + (kind == Kind.METHOD ? "()" : "");
    }
}
