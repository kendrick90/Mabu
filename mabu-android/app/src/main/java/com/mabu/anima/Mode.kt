package com.mabu.anima

enum class Mode {
    /** Eyes track the user's face, with lifelike saccades / glances / blinks. */
    FOLLOW,

    /** Robot mirrors the user: neck = head pose, eyes = pupil direction,
     *  eyelids = eye-openness. */
    PUPPET,

    /** No face input -- lifelike animations only (saccades / glances /
     *  blinks) on a centered baseline. Robot looks "alive and waiting". */
    IDLE,

    /** Eyelids fully closed, all motors centered, no animations. Robot "off". */
    SLEEP;

    fun next(): Mode = values()[(ordinal + 1) % values().size]
}
