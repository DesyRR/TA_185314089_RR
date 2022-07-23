/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package report;

import core.DTNHost;
import core.SimScenario;
import java.util.List;
import routing.ActiveRouter;
import routing.CVDetectionEngine;
import routing.CVTime;
import routing.DropRepDetectionEngine;
import routing.MessageRouter;

/**
 *
 * @author Windows_X
 */
public class DropRepsReport extends Report{
    public DropRepsReport() {
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
            DropRepDetectionEngine drEng = (DropRepDetectionEngine) ar;
            List<Double> dropRepsList = drEng.getDropRep();
            if (!dropRepsList.isEmpty()) {
                for (Double dropreps : dropRepsList) {
                    print += "\n" + dropreps;
                }
            }
        }
        write(print);
        super.done();
    }
}
