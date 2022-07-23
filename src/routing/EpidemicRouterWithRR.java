/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 *
 */
public class EpidemicRouterWithRR extends ActiveRouter implements CVDetectionEngine, DropRepDetectionEngine {

    public static final String EPIDEMICRR_NS = "EpidemicRouterWithRR";
    public static final String AI_S = "ai";
    public static final String MD_S = "md";
    public static final String ALPHA_CV = "alphaCV";

    public static final int DEFAULT_AI = 1;
    public static final double DEFAULT_MD = 0.2;
    public static final double DEFAULT_ALPHA = 0.9;

    // added - Retiring replicants's attributes -
    private int nrOfReps = 0; // initialize nr of reps
    private int nrOfDrops = 0; // initialize nr of drops
    private int limit = 1; // initialize limit
    protected double CV = 0; // initialize CV
    private int AI;  // Additive Increase value
    private double MD; // Multiplicative Decrease value
    public double ALPHA; // ALPHA
    private Map<Connection, Integer> connLimit; // Map to store conn and it's limit
    protected List<CVTime> cvList = new ArrayList<>(); // Record CV and Time
    protected List<Double> dropRepList = new ArrayList<>(); // Record drop/rep
    private Map<String, ACK_TTL> receiptBuffer; // buffer that save receipt
    /**
     * message that should be deleted
     */
    private Set<String> messageReadytoDelete;

    /**
     * Constructor. Creates a new message router based on the settings in the
     * given Settings object.
     *
     * @param s The settings object
     */
    public EpidemicRouterWithRR(Settings s) {
        super(s);
        Settings epidemicRRSettings = new Settings(EPIDEMICRR_NS);
        if (epidemicRRSettings.contains(AI_S)) {
            AI = epidemicRRSettings.getInt(AI_S);
        } else {
            AI = DEFAULT_AI;
        }
        if (epidemicRRSettings.contains(MD_S)) {
            MD = epidemicRRSettings.getDouble(MD_S);
        } else {
            MD = DEFAULT_MD;
        }
        if (epidemicRRSettings.contains(ALPHA_CV)) {
            ALPHA = epidemicRRSettings.getDouble(ALPHA_CV);
        } else {
            ALPHA = DEFAULT_ALPHA;
        }
        initConnLimit();
        this.receiptBuffer = new HashMap<>();
        this.messageReadytoDelete = new HashSet<>();
        //TODO: read&use epidemic router specific settings (if any)
    }

    /**
     * Copy constructor.
     *
     * @param r The router prototype where setting values are copied from
     */
    protected EpidemicRouterWithRR(EpidemicRouterWithRR r) {
        super(r);
        this.AI = r.AI;
        this.MD = r.MD;
        this.ALPHA = r.ALPHA;
        initConnLimit();
        this.receiptBuffer = new HashMap<>();
        this.messageReadytoDelete = new HashSet<>();
        //TODO: copy epidemic settings here (if any)
    }

    /**
     * Initializes Connection and Limit hash
     */
    private void initConnLimit() {
        this.connLimit = new HashMap<Connection, Integer>();
    }

    @Override
    public void changedConnection(Connection con) {
        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());

            connLimit.put(con, this.limit);
            System.out.println(this.limit);
            Collection<Message> thisMessageList = getMessageCollection();
            EpidemicRouterWithRR othRouter = (EpidemicRouterWithRR) otherHost.getRouter();

            // Exchange receipt buffer
            Map<String, ACK_TTL> peerReceiptBuffer = othRouter.getReceiptBuffer();

            for (Map.Entry<String, ACK_TTL> entry : peerReceiptBuffer.entrySet()) {
                if (!receiptBuffer.containsKey(entry.getKey())) {
                    receiptBuffer.put(entry.getKey(), entry.getValue());
                }
            }
            for (Message m : thisMessageList) {
                // Delete message that have a receipt
                if (receiptBuffer.containsKey(m.getId())) {
                    messageReadytoDelete.add(m.getId());
                }
            }

            for (String m : messageReadytoDelete) {
                if (isSending(m)) {
                    List<Connection> conList = getConnections();
                    for (Connection conn : conList) {
                        if (conn.getMessage() != null && conn.getMessage().getId() == m) {
                            conn.abortTransfer();;
                            break;
                        }
                    }
                }
                deleteMessage(m, false);
            }
            messageReadytoDelete.clear();
        } else {
            DTNHost otherHost = con.getOtherNode(getHost());
            double newCV = calculateCV(con, otherHost);
            CVTime cvValue = new CVTime(newCV, SimClock.getTime());
            cvList.add(cvValue);
            if (newCV <= this.CV) {
                this.limit = this.limit + AI;
            } else {
                this.limit = (int) Math.ceil(this.limit * MD);
            }
            this.CV = newCV;
            connLimit.remove(con);
            messageReadytoDelete.clear();
        }
    }

    /**
     * Calculate Congestion Value (CV)
     */
    private double calculateCV(Connection con, DTNHost peer) {
        EpidemicRouterWithRR othRouter = (EpidemicRouterWithRR) peer.getRouter();
        int hopCount = messagestotalHops();
        int drops = this.nrOfDrops + othRouter.nrOfDrops;
        int reps = this.nrOfReps + othRouter.nrOfReps + hopCount;
        // reset
        nrOfDrops = 0;
        nrOfReps = 0;
        // added
        double rasio;
        if (reps != 0) {
            rasio = (double) drops / (double) reps;
            dropRepList.add(rasio);
            return (ALPHA * rasio) + ((1.0 - ALPHA) * CV);
        } else {
            //rasio = (double) drops / 0.0001;
            //rasio = 1;
            return CV;
        }
    }

    /**
     * Counting total hops of messages
     */
    private int messagestotalHops() {
        Collection<Message> msg = getMessageCollection();
        int totalHops = 0;
        if (!msg.isEmpty()) {
            for (Message m : msg) {
                if (!(m.getHopCount() == 0)) {
                    totalHops += (m.getHopCount() - 1);
                }
            }
        }
        return totalHops;
    }

    @Override
    protected boolean makeRoomForMessage(int size) {
        if (size > this.getBufferSize()) {
            return false; // message too big for the buffer
        }

        int freeBuffer = this.getFreeBufferSize();
        /* delete messages from the buffer until there's enough space */
        while (freeBuffer < size) {
            Message m = getOldestMessage(true); // don't remove msgs being sent

            if (m == null) {
                return false; // couldn't remove any more messages
            }

            /* delete message from the buffer as "drop" */
            deleteMessage(m.getId(), true);
            this.nrOfDrops++;

            freeBuffer += m.getSize();
        }

        return true;
    }

    @Override
    public void update() {
        super.update();
        if (isTransferring() || !canStartTransfer()) {
            return; // transferring, don't try other connections yet
        }

        // Try first the messages that can be delivered to final recipient
        if (exchangeDeliverableMessages() != null) {
            return; // started a transfer, don't try others (yet)
        }

        // then try any/all message to any/all connection
        this.tryAllMessagesToAllConnections();
    }

    @Override
    protected int startTransfer(Message m, Connection con) {
        int retVal;

        if (!con.isReadyForTransfer()) {
            return TRY_LATER_BUSY;
        }
        if (connLimit.containsKey(con)) {
            retVal = con.startTransfer(getHost(), m);
            if (retVal == RCV_OK) { // started transfer
                addToSendingConnections(con);
                int remainingLimit = connLimit.get(con) - 1; // setiap koneksi pnya limit jika selesai tf, limit -1
                if (remainingLimit != 0) {
                    connLimit.replace(con, remainingLimit);
                } else {
                    connLimit.remove(con);
                }
            } else if (deleteDelivered && retVal == DENIED_OLD && m.getTo() == con.getOtherNode(this.getHost())) {
                /* final recipient has already received the msg -> delete it*/
                this.deleteMessage(m.getId(), false);

            }
            return retVal;
        }
        return DENIED_UNSPECIFIED;
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        this.nrOfReps++;
        // - ACK -
        if (isFinalDest(msg, this.getHost()) && !receiptBuffer.containsKey(msg.getId())) {
            ACK_TTL ack = new ACK_TTL(SimClock.getTime(), msg.getTtl());
            receiptBuffer.put(msg.getId(), ack);
        }
        return msg;
    }

    // check if this host is final destination
    public boolean isFinalDest(Message m, DTNHost aHost) {
        /*   if (m.getTo().equals(aHost)) {
            return true;
        }*/
        return m.getTo().equals(aHost);
    }

    @Override
    public EpidemicRouterWithRR replicate() {
        return new EpidemicRouterWithRR(this);
    }

    @Override
    public List<CVTime> getCongestionValue() {
        return this.cvList;
    }

    public Map<String, ACK_TTL> getReceiptBuffer() {
        return receiptBuffer;
    }

    @Override
    public List<Double> getDropRep() {
        return this.dropRepList;
    }
}
