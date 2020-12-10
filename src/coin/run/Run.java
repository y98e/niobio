package coin.run;

import coin.util.*;

public class Run {

	public static void run(final String run) {
		while (true) {
			try {
				Process p = null;

				p = Util.exec("git reset --hard origin/master");
				p.waitFor();

				p = Util.exec(
						"javac --module-path ./lib/bcprov-jdk15on-1.66.jar:./lib/postgresql-42.2.18.jar ./src/coin/crypto/*.java ./src/coin/daemon/*.java ./src/coin/miner/*.java ./src/coin/run/*.java ./src/coin/util/*.java ./src/coin/wallet/*.java ./src/org/json/*.java -d ./bin");
				p.waitFor();

				p = Util.exec(
						"java -Dfile.encoding=UTF-8 -cp ./lib/bcprov-jdk15on-1.66.jar:./lib/postgresql-42.2.18.jar:./bin "
								+ run);
				p.waitFor();
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
	}

}
