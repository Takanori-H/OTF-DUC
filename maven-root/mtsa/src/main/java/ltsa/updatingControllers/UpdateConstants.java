package ltsa.updatingControllers;

/**
 * Created by Victor Wjugow on 05/06/15.
 */
public class UpdateConstants {
	public static final String RECONFIGURE = "reconfigure";
	public static final String BEGIN_UPDATE = "hotSwapIn";
	public static final String START_NEW_SPEC = "startNewSpec";
	public static final String STOP_OLD_SPEC = "stopOldSpec";
	public static final String STOP_OLD_SPEC_PREFIX = "stopOldSpec_";
	public static final String RECONFIGURE_PREFIX = "reconfigure_";
	public static final String START_NEW_SPEC_PREFIX = "startNewSpec_";
	public static final String STOP_OLD_SPEC_OTHERS = STOP_OLD_SPEC_PREFIX + "others";
	public static final String RECONFIGURE_OTHERS = RECONFIGURE_PREFIX + "others";
	public static final String START_NEW_SPEC_OTHERS = START_NEW_SPEC_PREFIX + "others";
	public static final String OLD_LABEL = ".old";
	public static final String OLD_SUFFIX = "_UPD_OLD";
	public static final String NEW_SUFFIX = "_UPD_NEW";
	public static final String FINISH_UPDATE = "hotSwapOut";

	//ON-THE-FLY UPDATING CONTROLLER SYNTHESIS
	public static final String HOTSWAP_BEGIN = BEGIN_UPDATE;
	public static final String HOTSWAP_END = FINISH_UPDATE;
}
