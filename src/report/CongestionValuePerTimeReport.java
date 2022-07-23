package report;

import core.DTNHost;
import core.SimScenario;
import java.util.List;
import routing.ActiveRouter;
import routing.CVDetectionEngine;
import routing.CVTime;
import routing.MessageRouter;

/**
 *
 * @author Desy
 */
public class CongestionValuePerTimeReport extends Report {

    public CongestionValuePerTimeReport() {
        init();
    }

    @Override
    public void done() {
        String print = "";
        List<DTNHost> nodes = SimScenario.getInstance().getHosts();
        for (DTNHost h : nodes) {
            print += "\n========================================================\n" + h + "\n";
            MessageRouter r = h.getRouter();
            ActiveRouter ar = (ActiveRouter) r;
            CVDetectionEngine cvEng = (CVDetectionEngine) ar;
            List<CVTime> cvList = cvEng.getCongestionValue();
            if (!cvList.isEmpty()) {
                for (CVTime CV : cvList) {
                    print += "\n" + CV.CV + "\t" + CV.time;
                }
            }
        }
        write(print);
        super.done();
    }
}
