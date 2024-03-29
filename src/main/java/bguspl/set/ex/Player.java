package bguspl.set.ex;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import bguspl.set.Env;

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
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * Player actions queue.
     */
    private BlockingQueue<Integer> actions;

    private long timeToFreeze;

    private Object waitingUntilDealerCheck;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.actions = new LinkedBlockingQueue<Integer>(3);
        this.timeToFreeze = 0;
        this.terminate = false;
        this.aiThread = null;
        this.waitingUntilDealerCheck = new Object();
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();
        while(!terminate) {
            needToFreeze(); // Checks if a player thread needs to sleep (Because of penalty or point)
            while (timeToFreeze == 0 && !this.actions.isEmpty() && !Thread.currentThread().isInterrupted()) {
                try {
                    placeOrRemoveToken(this.actions.take());
                } catch (InterruptedException e) {
                    env.logger.info("thread " + Thread.currentThread().getName() + " interrupted");
                }
            }
        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Random random = new Random();
                keyPressed(random.nextInt(env.config.tableSize));
                try {
                    synchronized (this) {
                        wait(5);
                    }
                } 
                catch (InterruptedException ignored) 
                {
                    env.logger.info("thread " + Thread.currentThread().getName() + " interrupted.");
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
        if(this.aiThread != null){
            this.aiThread.interrupt();
        }
    }

    public void needToFreeze() {
        if (timeToFreeze > 0) {
            for (long i = timeToFreeze; i > 0 && !terminate; i -= 1000) {
                try {
                    env.ui.setFreeze(this.id, i);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    env.logger.info("thread " + Thread.currentThread().getName() + " interrupted");
                    this.terminate();
                }
            }
            if(!terminate)
            {
                timeToFreeze = 0;
                env.ui.setFreeze(id, timeToFreeze);
            }
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (this.table.slotToCard[slot] != null && timeToFreeze == 0)
            try {
                this.actions.put(slot);
            } catch (InterruptedException e) {
                env.logger.info("thread " + Thread.currentThread().getName() + "interrupted");
            }
    }

    /**
     * Executes player keypressed , if player already have token on specified
     * slot,
     * remove it , else place it. (max tokens:3)
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void placeOrRemoveToken(int slot) {
        synchronized(this.table.getSlotLocks()[slot])
        {
            if(this.table.slotToCard[slot] != null && !this.table.removeToken(this.id, slot) && this.table.getCountTokensByPlayer(this.id) != env.config.featureSize)
            {
                this.table.placeToken(id, slot);
            }
        }
        if (this.table.getCountTokensByPlayer(this.id) == env.config.featureSize)
        {
            
            synchronized(this.waitingUntilDealerCheck)
            {
                try
                {
                    this.table.addPlayerWith3Tokens(this.id);
                    env.logger.info("thread " + Thread.currentThread().getName() + " waiting for dealer check");
                    this.waitingUntilDealerCheck.wait();
                    env.logger.info("thread " + Thread.currentThread().getName() + " waked up by dealer");
                }
                catch (InterruptedException e)
                {
                    env.logger.info("thread " + Thread.currentThread().getName() + "interrupted");
                }
            }
        }           
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setScore(this.id, ++this.score);
        timeToFreeze = env.config.pointFreezeMillis;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        timeToFreeze = env.config.penaltyFreezeMillis;
    }

    public int score() {
        return score;
    }

    public long getTimeToFreeze() {
        return this.timeToFreeze;
    }
    
    public Thread getPlayerThread()
    {
        return this.playerThread;
    }

    public Object getWaitingUntilDealerCheck()
    {
        return this.waitingUntilDealerCheck;
    }
}
