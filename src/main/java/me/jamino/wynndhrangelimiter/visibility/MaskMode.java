package me.jamino.wynndhrangelimiter.visibility;

public enum MaskMode {
    PASSTHROUGH,
    MAIN,
    LIGHT,
    VOID_OUTER,
    NONE,
    /** Test-only: one arbitrary rectangle, selectable only through the designated local fixture. */
    FIXTURE_CUSTOM
}
