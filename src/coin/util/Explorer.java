package coin.util;

import java.io.*;
import java.sql.*;
import java.util.*;

import org.json.*;
import org.postgresql.util.*;

import coin.crypto.*;

public class Explorer {

	private static final String url = "jdbc:postgresql://localhost:15432/niobio";
	private static final String user = "postgres";
	private static final String password = "Postgres2020!";

	private static Connection conn;

	public static void connect() throws SQLException {
		conn = DriverManager.getConnection(url, user, password);

		if (conn != null) {
			System.out.println("Connected to the PostgreSQL server successfully.");
		} else {
			System.out.println("Failed to make connection!");
		}
	}

	public static void main(final String[] args) throws SQLException, ClassNotFoundException, IOException {
		connect();
		final File directoryPath = new File("./data/blockchain/");
		final String contents[] = directoryPath.list();
		Arrays.sort(contents);
		for (int i = 0; i < contents.length; i++) {
			if ("snapshot.json".equals(contents[i])) continue;
			final File f = new File("./data/blockchain/" + contents[i]);
			if (f.isDirectory()) {
				final String subContents[] = f.list();
				Arrays.sort(subContents);
				for (int j = 0; j < subContents.length; j++) {
					addJSON("./data/blockchain/" + contents[i] + "/" + subContents[j]);
				}
			} else {
				addJSON("./data/blockchain/" + contents[i]);
			}
		}
	}

	private static void addJSON(final String jsonFile) throws ClassNotFoundException, IOException, SQLException {
		final Obj block = Util.loadObjFromFile(jsonFile);
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
			System.out.println(jsonFile);
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

}
