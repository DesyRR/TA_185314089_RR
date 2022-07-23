package routing;

import core.DTNHost;
import java.util.List;

/**
 *
 * @author Desy
 */
public interface CVDetectionEngine {
    public List<CVTime> getCongestionValue();
}
