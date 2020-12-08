package coin.util;

import java.io.*;
import java.sql.*;
import java.util.*;

import org.json.*;
import org.postgresql.util.*;

import com.sun.net.httpserver.*;

import coin.crypto.*;
import coin.daemon.*;

public class Explorer {

	private static final String url = "jdbc:postgresql://localhost:15432/niobio";
	private static final String user = "postgres";
	private static final String password = "Postgres2020!";

	private static Connection conn;

	static {
		try {
			conn = DriverManager.getConnection(url, user, password);
		} catch (final SQLException e) {
			// throw new RuntimeException(e.getMessage());
		}
	}

	public static void main(final String[] args) throws SQLException, ClassNotFoundException, IOException {

		Thread.currentThread().setName("Explorer");
		if (conn == null) throw new RuntimeException("Explorer off");

		Util.p("INFO: Starting Explorer");

		loadExplorerBlocks();

		// http server = another thread (handleRequest is the "main" method)
		Util.startHttpServer(Util.conf.getInt("walletRPC"), x -> {
			try {
				handleRequest(x);
			} catch (final Exception e) {
				e.printStackTrace();
				System.exit(0);
			}
		});

	}

	private static void addBlock(final Obj block) throws SQLException, IOException {
		final Arr txs = block.getArr("txs");
		String sql = "INSERT INTO blockchain (blocktext, block, blockhash) VALUES (to_json(?::json), to_json(?::json), ?)";

		PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

		pstmt.setString(1, block.toString());
		pstmt.setString(2, block.toString());
		String sha = Crypto.sha(block);
		pstmt.setString(3, sha);

		try {
			pstmt.executeUpdate();
		} catch (final PSQLException e) {
			return;
		}

		final ResultSet rs = pstmt.getGeneratedKeys();
		rs.next();
		final long id = rs.getLong(1);

		for (int i = 0; i < txs.length(); i++) {
			final Obj tx = txs.getObj(i);
			sql = "INSERT INTO tx (txtext, txhash, block_id) VALUES (to_json(?::json), ?, ?)";
			pstmt = conn.prepareStatement(sql);
			pstmt.setString(1, tx.toString());
			sha = Crypto.sha(tx);
			pstmt.setString(2, sha);
			pstmt.setLong(3, id);
			pstmt.executeUpdate();
		}
	}

	private static Obj getBlock(final String hash) throws SQLException {
		final String sql = "SELECT blocktext FROM blockchain WHERE blockhash = ?";
		final PreparedStatement pstmt = conn.prepareStatement(sql);
		pstmt.setString(1, hash);
		final ResultSet rs = pstmt.executeQuery();
		String json = "{}";
		if (rs.next()) {
			json = rs.getString("blocktext");
		}
		return new Obj(json);
	}

	private static Obj getTransaction(final String hash) throws SQLException {
		final String sql = "SELECT txtext, blockhash FROM tx t, blockchain b WHERE t.block_id = b.id AND txhash = ?";
		final PreparedStatement pstmt = conn.prepareStatement(sql);
		pstmt.setString(1, hash);
		final ResultSet rs = pstmt.executeQuery();
		String json = "{}";
		Obj obj = null;
		if (rs.next()) {
			json = rs.getString("txtext");
			obj = new Obj(json);
			obj.put("blockhash", rs.getString("blockhash"));
		} else {
			obj = new Obj(json);
		}
		return obj;
	}

	private static void loadExplorerBlocks() throws ClassNotFoundException, JSONException, IOException, SQLException {

		long h = 1;
		String fileName = null;

		if (!new File(Util.conf.getString("snapshot")).exists()) {
			Util.p("INFO: NO snapshot. Loading from the begining (height=0)");

		} else {
			Util.p("INFO: loading snapshot");
			final Obj snapshot = Util.loadObjFromFile(Util.conf.getString("snapshot"));
			h = snapshot.getLong("height");
			Util.p("INFO: chain height: " + h);

		}

		fileName = DUtil.getBlockFileName(h, 1);

		final File directoryPath = new File("./data/blockchain/");
		final String contents[] = directoryPath.list();
		Arrays.sort(contents);
		boolean findIt = false;
		for (int i = 0; i < contents.length; i++) {
			if ("snapshot.json".equals(contents[i])) continue;
			final File f = new File("./data/blockchain/" + contents[i]);
			if (f.isDirectory()) {
				final String subContents[] = f.list();
				Arrays.sort(subContents);
				for (int j = 0; j < subContents.length; j++) {
					if (!findIt && !fileName.contains(subContents[j])) {
						continue;
					} else {
						findIt = true;
					}
					final Obj block = Util.loadObjFromFile("./data/blockchain/" + contents[i] + "/" + subContents[j]);
					addBlock(block);
				}
			} else {
				final Obj block = Util.loadObjFromFile("./data/blockchain/" + contents[i]);
				addBlock(block);
			}
		}

	}

	static void handleRequest(final HttpExchange exchange) throws Exception {
		Thread.currentThread().setName("RPC Explorer " + Util.random.nextInt(100));
		Obj json = Util.inputStreamToJSON(exchange.getRequestBody());

		if (json.has("method")) {

			final String method = json.getString("method");

			switch (method) {
			case "getBlock":
				json = getBlock(json.getString("hash"));
				Util.response(exchange, json);
				break;

			case "getTransaction":
				json = getTransaction(json.getString("hash"));
				break;
			}

		} else {
			addBlock(json);
		}

	}

}
