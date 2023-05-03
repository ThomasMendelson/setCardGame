package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long timeWhenReset;
    protected Thread[] playerThreads;
    public Object dealerTerminateLock;
    public Object dealerLock = new Object();
    public LinkedList<Player> wasInteruppted;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        wasInteruppted = new LinkedList<Player>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        playerThreads = new Thread[players.length];
        for (int i = 0; i < players.length; i++) {
            Thread playerThread = new Thread (players[i] , "player "+i);
            playerThreads[i] = playerThread;
            playerThread.start();
            synchronized (this){
                try{
                    this.wait();
                }
                catch (InterruptedException ignored) {}
            }

        }
        while (!shouldFinish()) {
            shuffleDeck();
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            try {
                Player.slotLock.acquire();
                removeAllCardsFromTable();
                for (int i = 0; i < playerThreads.length; i++) {
                    players[i].noPointNopenalty = true;
                }
                wasInteruppted.clear();
                synchronized (dealerLock){
                    dealerLock.notifyAll();
                }
                Player.slotLock.release();
            }
            catch (InterruptedException ignored){}
        }
        announceWinners();
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }

    private void shuffleDeck() {
        Collections.shuffle(deck);
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        boolean toShuffle = false;
        if (env.config.turnTimeoutMillis>0){
            if (System.currentTimeMillis() - timeWhenReset>=env.config.turnTimeoutMillis)
                toShuffle = true;
            else if (checkLegalMoves())
                toShuffle = env.util.findSets(deck, 1).size() == 0;
        }
        while (!terminate && !toShuffle) {
            Player hasAClaim = null;
            if (env.config.turnTimeoutMillis>0){
                if ((System.currentTimeMillis() - timeWhenReset>=env.config.turnTimeoutMillis))
                    toShuffle = true;
                else if (checkLegalMoves()){
                    toShuffle = env.util.findSets(deck, 1).size() == 0;
                }

            }
            else {
                toShuffle = checkLegalMoves();
            }
            sleepUntilWokenOrTimeout();
            if (wasInteruppted.size()>0){
                hasAClaim = wasInteruppted.removeLast();

            }
            updateTimerDisplay(false);
            awardOrpenalized(hasAClaim);
            placeCardsOnTable();
        }
    }

    private boolean checkLegalMoves() {
        List<Integer> onTable = new LinkedList<>();
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i]!=null)
                onTable.add(table.slotToCard[i]);
        }
        return env.util.findSets(onTable, 1).size() == 0;
    }

    private void awardOrpenalized(Player hasAClaim) {
        if (hasAClaim==null){
            return;
        }
        int[] myCards = new int[3];
        int first = table.slotToCard[hasAClaim.tokenPlacment[0]];
        int second = table.slotToCard[hasAClaim.tokenPlacment[1]];
        int third = table.slotToCard[hasAClaim.tokenPlacment[2]];
        myCards[0] = first;
        myCards[1] = second;
        myCards[2] = third;
        if (env.util.testSet(myCards)){
            hasAClaim.shouldBeRewarded = true;
            removeCardsFromTable(hasAClaim.tokenPlacment);
            synchronized (dealerLock) {
                dealerLock.notifyAll();
            }
            updateTimerDisplay(true);
            return;
        }
        else {
            hasAClaim.shouldBeRewarded = false;
            synchronized (dealerLock) {
                dealerLock.notifyAll();
            }
            return;
        }
    }


    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate =true;
    }

    private void finishProgram() {
        for (int i =players.length-1 ; i>=0 ;i--) {
            players[i].terminate();
            synchronized (players[i].playerThread){
                players[i].playerThread.notifyAll();
            }
            try {
                playerThreads[i].join();
            } catch (InterruptedException ignore) {
            }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks if any cards should be removed from the table and returns them to the deck.
     */
    protected void removeCardsFromTable(int [] slotsToRemove) {
        if (slotsToRemove == null)
            return;
        int[] ToRemove = {slotsToRemove[0],slotsToRemove[1],slotsToRemove[2]};

        try {
            Player.slotLock.acquire();
        } catch (InterruptedException ignored) {
        }
        for (int i = 0; i < ToRemove.length; i++) {
            table.removeCard(ToRemove[i]);
        }
        for (int j = 0; j < players.length; j++) {
            for (int i = 0; i < ToRemove.length; i++) {
                players[j].removeTokenFromSlot(ToRemove[i]);
            }
        }
        Player.slotLock.release();

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int i = 0; i < table.slotToCard.length; i++) {
            if(table.slotToCard[i] == null && !deck.isEmpty()){
                Integer toPlace = deck.remove(0);
                table.placeCard(toPlace, i);
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout(){
        long timeToSleep = 1000;                            //Defualt Sleep is 1 sc
        if (env.config.turnTimeoutMillis>0 && env.config.turnTimeoutWarningMillis>(env.config.turnTimeoutMillis-(System.currentTimeMillis()-timeWhenReset)))
            timeToSleep = Math.min(timeToSleep,10);
        synchronized (dealerLock){
            try {
                if(wasInteruppted.size()==0){
                    dealerLock.wait(timeToSleep);
                }

            } catch (InterruptedException ignored) {
            }

        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset){
            resetTime();
        }
        else {
            // System.out.println("updateTimerDisplay1");
            long currentTime = System.currentTimeMillis();
            long timePast = currentTime - timeWhenReset;
            if (env.config.turnTimeoutMillis>0){
                if (env.config.turnTimeoutWarningMillis>env.config.turnTimeoutMillis-timePast)
                    if (env.config.turnTimeoutMillis-timePast<0)
                        env.ui.setCountdown(0,true);
                    else
                        env.ui.setCountdown(env.config.turnTimeoutMillis-timePast,true);
                else{
                    double doubleTimeToDisplay =  (double)((env.config.turnTimeoutMillis-timePast))/1000;
                    long timeToDisplay = (Long)(Math.round(doubleTimeToDisplay)*1000);
                    env.ui.setCountdown(timeToDisplay,false);

                }
            }
            else if (env.config.turnTimeoutMillis==0)
                env.ui.setElapsed(timePast);
        }
    }

    private void resetTime (){
        timeWhenReset = System.currentTimeMillis();
        if (env.config.turnTimeoutMillis>0){
            boolean toWarn = false;
            if (env.config.turnTimeoutMillis <= env.config.turnTimeoutWarningMillis)
                toWarn = true;
            env.ui.setCountdown(env.config.turnTimeoutMillis,toWarn);
        }
        if (env.config.turnTimeoutMillis == 0)
            env.ui.setElapsed(0);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i]!=null){
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
            
            for (int j = 0; j < players.length; j++) {
                players[j].tokenCount = 0;
                players[j].tokenPlacment[0] = -1;
                players[j].tokenPlacment[1] = -1;
                players[j].tokenPlacment[2] = -1;
                players[j].keyPressedclear();
                if (!players[j].getHuman())
                    synchronized(players[j].playerAILock){
                        players[j].playerAILock.notifyAll();
                    }
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    protected void announceWinners() {
        int count = 1;
        int bestScore = 0;
        for (int i = 0; i < players.length; i++) {
            if(bestScore < players[i].score()){
                count = 1;
                bestScore = players[i].score();
            }
            else if (bestScore == players[i].score())
                count ++;
        }
        int[] allWinners = new int[count];
        count=0;
        for (int i = 0; i < players.length; i++) {
            if(players[i].score() == bestScore){
                allWinners[count] = players[i].id;
                count++;
            }
        }
        env.ui.announceWinner(allWinners);
        finishProgram();
    }
}