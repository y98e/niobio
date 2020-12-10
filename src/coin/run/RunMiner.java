package coin.run;

public abstract class RunMiner extends Run {

	public static void main(final String[] args) {
		run("coin.miner.Miner " + args[0]);
	}
}
