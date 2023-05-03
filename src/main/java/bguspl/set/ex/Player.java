package bguspl.set.ex;

import bguspl.set.Env;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    protected volatile boolean terminate;

    /**
     * The current score of the player.
     */

    private volatile ConcurrentLinkedQueue<Integer> keyPressed;
    private int score;
    private Dealer myDealer;


    public boolean shouldBeRewarded = false;
    public volatile int tokenCount = 0;
    public volatile int[] tokenPlacment = {-1, -1, -1};
    public Object playerAILock = new Object();
    static public Semaphore slotLock = new Semaphore(1,true);
    static public Semaphore smpr = new Semaphore(1,true);
    public boolean noPointNopenalty = false;



    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.keyPressed = new ConcurrentLinkedQueue<Integer>();
        this.myDealer = dealer;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        if (!human) createArtificialIntelligence();
        synchronized (myDealer){
            myDealer.notifyAll();
        }
        while (!terminate) {
            synchronized (playerThread) {
                while (keyPressed.isEmpty() && !terminate) {
                    try{
                        Thread.sleep(1);
                    }
                    catch (InterruptedException ignore){}
                    try {
                        playerThread.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            placeToken();
        }
        if (!human) try {
            synchronized (playerAILock){
                playerAILock.notifyAll();
            }
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }



    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
            while (!terminate) {
                synchronized (playerAILock) {
                    while (keyPressed.size()==3 && !terminate) {
                        try {
                            playerAILock.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }   
                int x;
                do{
                    if (tokenCount<3) {
                        do {
                            x = (int) (Math.random() * env.config.tableSize);
                        }
                        while (!isOkSlot(x));
                    }
                    else {
                        x = tokenPlacment[(int) (Math.random() * 3)];
                    }
                } while (x==-1);
                keyPressed(x);
            }
            System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    public boolean isOkSlot(int x){
        boolean toReturn = true;
        for (int i = 0;i<tokenPlacment.length&&toReturn;i++){
            if (tokenPlacment[i]==x)
                toReturn = false;
        }
        return toReturn;
    }
    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        synchronized (playerThread){
            if (keyPressed.size() < 3) {
                keyPressed.add(slot);
                playerThread.notifyAll();
            }
        }
    }

    private void placeToken() {
        boolean found = false;
        boolean placed = false;
        int slot = -1;
        synchronized (playerAILock){
            if (!keyPressed.isEmpty())
                slot = (Integer) keyPressed.remove();
            if (!human)
                playerAILock.notifyAll();
        }
        if (slot!=-1) {
            try{
                slotLock.acquire();
                    for (int i = 0; i < 3; i++) {
                        if (tokenPlacment[i] == slot) {
                            tokenPlacment[i] = -1;
                            tokenCount--;
                            env.ui.removeToken(this.id,slot);
                            found = true;
                        }
                    }
                    if (!found && table.slotToCard[slot]!=null) {
                        for (int i = 0; i < 3 && !placed; i++) {
                            if (tokenPlacment[i] == -1) {
                                tokenCount++;
                                tokenPlacment[i] = slot;
                                table.placeToken(id, slot);
                                placed = true;
                            }
                        }
                    }
                slotLock.release();
            }catch (InterruptedException ignored) {}
            try {
                smpr.acquire();
                if (placed && tokenCount == 3){
                    claimToAPoint();
                }
                else
                    smpr.release();
            } catch (InterruptedException ignored) {
            }
        }
    }
    

    private void claimToAPoint() {
        synchronized (myDealer.dealerLock){
            myDealer.wasInteruppted.addFirst(this);
            myDealer.dealerLock.notifyAll();
                try {
                    myDealer.dealerLock.wait();
               } catch (InterruptedException e) {
               }
        }
        smpr.release();
        if (!noPointNopenalty)
            if (shouldBeRewarded)
                point();
            else
                penalty();
        else
            noPointNopenalty = false;
    }


    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setScore(id, ++score);
        env.ui.setFreeze(this.id,env.config.pointFreezeMillis);
        try {
            long timeToSleep = env.config.pointFreezeMillis;
            while (timeToSleep>=1000) {
                timeToSleep = timeToSleep - 1000;
                Thread.sleep(1000);
                env.ui.setFreeze(this.id, timeToSleep);
            }

        } catch (InterruptedException ignore) {
        }
        env.ui.setFreeze(this.id, 0);
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        env.ui.setFreeze(this.id,env.config.penaltyFreezeMillis);
        try {
            long timeToSleep = env.config.penaltyFreezeMillis;
            while (timeToSleep>=1000) {
                timeToSleep = timeToSleep - 1000;
                Thread.sleep(1000);
                env.ui.setFreeze(this.id, timeToSleep);
            }
        } catch (InterruptedException ignored) {
        }
        env.ui.setFreeze(this.id, 0);
    }

    public int score() {
        return score;
    }



    public void removeTokenFromSlot(int slot) {
        if (tokenPlacment[0] == slot) {
            tokenPlacment[0] = -1;
        } if (tokenPlacment[1] == slot) {
            tokenPlacment[1] = -1;
        } if (tokenPlacment[2] == slot) {
            tokenPlacment[2] = -1;
        }
        tokenCount = 0;
        for (int i = 0; i < tokenPlacment.length; i++) {
            if (tokenPlacment[i]!=-1)
            tokenCount++;
        }

    }
    public void keyPressedclear(){
        keyPressed.clear();
    }

    public boolean getHuman (){
        return human;
    }

}
