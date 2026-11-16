package io.github.yudigaga.lightfreeze.core;

public final class Protocol {
    public static final String PACKAGE = "io.github.yudigaga.lightfreeze";
    public static final String ACTION = PACKAGE + ".CONTROL";
    public static final String PERMISSION = PACKAGE + ".permission.CONTROL";
    public static final String STATUS = "STATUS";
    public static final String SYNC_CONFIG = "SYNC_CONFIG";
    public static final String ACQUIRE = "ACQUIRE";
    public static final String BEGIN_SESSION = "BEGIN_SESSION";
    public static final String FINISH = "FINISH";
    public static final String ABORT_LAUNCH = "ABORT_LAUNCH";
    public static final String SELF_FREEZE = "SELF_FREEZE";
    public static final String CHECK_FREEZE = "CHECK_FREEZE";
    public static final String SYNC_FROZEN_URLS = "SYNC_FROZEN_URLS";
    public static final String KEEP_SELF_ENABLED = "KEEP_SELF_ENABLED";
    public static final String BEGIN_FROZEN_URL = "BEGIN_FROZEN_URL";
    public static final String END_FROZEN_URL = "END_FROZEN_URL";
    public static final String FROZEN_URL_TICKET = PACKAGE + ".extra.FROZEN_URL_TICKET";
    private Protocol() {}
}
