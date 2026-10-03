package com.ava.mods.respeaker;

/**
 * One control parameter of the XVF3800.
 *
 * The XMOS protocol addresses every parameter by a pair of resource ID and
 * command ID. The values come from python_control in Seeed's repository and
 * were checked against the device - nothing here is guessed.
 */
final class XvfParam {

    static final int T_UINT8 = 0;
    static final int T_INT32 = 1;
    static final int T_FLOAT = 2;

    final String name;
    final int resid;
    final int cmdid;
    final int count;
    final int type;

    private XvfParam(String name, int resid, int cmdid, int count, int type) {
        this.name = name;
        this.resid = resid;
        this.cmdid = cmdid;
        this.count = count;
        this.type = type;
    }

    /** Size of a single value in bytes. */
    int itemSize() {
        return type == T_UINT8 ? 1 : 4;
    }

    /** Payload plus the leading status byte the firmware prepends. */
    int readLength() {
        return count * itemSize() + 1;
    }

    /** Firmware version. Doubles as the liveness check of the control channel. */
    static final XvfParam VERSION =
        new XvfParam("VERSION", 48, 0, 3, T_UINT8);

    /**
     * Direction of the four beams, in radians.
     *
     * With the fixed beams switched on, index 0 and 1 hold the written set
     * point while 2 and 3 keep running freely. That is what lets this mod
     * hold and measure at the same time. Reading the direction from index 0
     * returns your own set point and confirms nothing.
     */
    static final XvfParam AZIMUTH =
        new XvfParam("AEC_AZIMUTH_VALUES", 33, 75, 4, T_FLOAT);

    /** Speech energy per beam. Without it the tracker follows noise. */
    static final XvfParam SPENERGY =
        new XvfParam("AEC_SPENERGY_VALUES", 33, 80, 4, T_FLOAT);

    /** Set direction of the two fixed beams, in radians. */
    static final XvfParam FIXED_AZIMUTH =
        new XvfParam("AEC_FIXEDBEAMSAZIMUTH_VALUES", 33, 81, 2, T_FLOAT);

    static final XvfParam FIXED_ELEVATION =
        new XvfParam("AEC_FIXEDBEAMSELEVATION_VALUES", 33, 82, 2, T_FLOAT);

    static final XvfParam FIXED_ONOFF =
        new XvfParam("AEC_FIXEDBEAMSONOFF", 33, 37, 1, T_INT32);

    /**
     * Mutes everything outside the beam. It depends on the direction
     * estimate, not on the beam width, so the gate closes completely at
     * about 17 degrees of error. Off by default.
     */
    static final XvfParam FIXED_GATING =
        new XvfParam("AEC_FIXEDBEAMSGATING", 33, 83, 1, T_UINT8);

    /**
     * Routing of the two USB output channels: two uint8 each, category and
     * source. Factory state is right 7/3 (ASR, auto-select beam) and left
     * 8/0 (conference). Ava records mono - Android folds both channels into
     * one - so switching only one channel reaches at most half of what Ava
     * hears.
     */
    static final XvfParam OP_R =
        new XvfParam("AUDIO_MGR_OP_R", 35, 19, 2, T_UINT8);

    static final XvfParam OP_L =
        new XvfParam("AUDIO_MGR_OP_L", 35, 15, 2, T_UINT8);

    /**
     * LED ring, USB firmware 2.0.7 and later. Effect: 0 off, 1 breathe,
     * 2 rainbow, 3 single colour, 4 direction of arrival (factory), 5 ring.
     * Colours as 0xRRGGBB.
     */
    static final XvfParam LED_EFFECT =
        new XvfParam("LED_EFFECT", 20, 12, 1, T_UINT8);

    /** Brightness for breathe and rainbow, 0..255. */
    static final XvfParam LED_BRIGHTNESS =
        new XvfParam("LED_BRIGHTNESS", 20, 13, 1, T_UINT8);

    /**
     * Speed for breathe and rainbow, 0..255. Seeed's console defaults to 64,
     * its example uses 1 for a calm breathe.
     */
    static final XvfParam LED_SPEED =
        new XvfParam("LED_SPEED", 20, 15, 1, T_UINT8);

    /** Colour for breathe and single colour. */
    static final XvfParam LED_COLOR =
        new XvfParam("LED_COLOR", 20, 16, 1, T_INT32);

    /** One colour per LED in the ring effect. */
    static final XvfParam LED_RING_COLOR =
        new XvfParam("LED_RING_COLOR", 20, 19, 12, T_INT32);
}
