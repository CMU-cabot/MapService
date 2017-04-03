/*******************************************************************************
 * Copyright (c) 2014, 2017  IBM Corporation, Carnegie Mellon University and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *******************************************************************************/
package hulop.hokoukukan.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;

public class MongoAdapter implements DBAdapter {

	private final DB db;
	private final GridFS mFS;
	private final MongoClientURI mongoURI;
	private final DBCollection mapCol, userCol, logCol, fileCol, entryCol;
	private final List<DBObject> insertList = new ArrayList<DBObject>();
	private final List<DBObject> insertLogList = new ArrayList<DBObject>();
	private int insertCount = 0;

	public MongoAdapter(String url) throws UnknownHostException {
		mongoURI = new MongoClientURI(url);
		db = new MongoClient(mongoURI).getDB(mongoURI.getDatabase());
		mFS = new GridFS(db);
		System.out.println(db.getCollectionNames());
		mapCol = db.getCollection("maps");
		userCol = db.getCollection("users");
		logCol = db.getCollection("logs");
		fileCol = db.getCollection("files");
		entryCol = db.getCollection("entries");

		mapCol.createIndex(new BasicDBObject("geometry", "2dsphere"));
	}

	@Override
	public void prepare(File file) {
		if (file != null) {
			mapCol.remove(new BasicDBObject("properties.file", file.getPath()));
		}
		insertList.clear();
		insertLogList.clear();
		insertCount = 0;
	}

	@Override
	public void insert(String json) {
		insertList.add((DBObject) com.mongodb.util.JSON.parse(json));
		if (insertList.size() >= 1000) {
			flush();
		}
	}

	@Override
	public void update(String json) {
		mapCol.save((DBObject) com.mongodb.util.JSON.parse(json));
	}

	@Override
	public void remove(JSONArray array) {
		List<String> idList = new ArrayList<String>();
		for (int i = 0; i < array.length(); i++) {
			try {
				JSONObject obj = array.getJSONObject(i);
				if (obj.has("_id")) {
					idList.add(obj.getString("_id"));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		mapCol.remove(new BasicDBObject("_id", new BasicDBObject("$in", idList)));
	}

	@Override
	public void flush() {
		if (insertList.size() > 0) {
			mapCol.insert(insertList);
			insertCount += insertList.size();
			insertList.clear();
		}
		if (insertLogList.size() > 0) {
			logCol.insert(insertLogList);
			insertCount += insertLogList.size();
			insertLogList.clear();
		}
	}

	@Override
	public int getInsertCount() {
		return insertCount;
	}

	@Override
	public JSONArray getResult() {
		return new JSONArray();
	}

	@Override
	public void dropDB() {
		db.dropDatabase();
	}

	@Override
	public void getGeometry(double[] center, double radius, JSONObject nodeMap, JSONArray features,
			List<String> categories) {
		DBObject query = new BasicDBObject()
				.append("geometry",
						new BasicDBObject("$near",
								new BasicDBObject("$geometry",
										new BasicDBObject("type", "Point").append("coordinates", center))
												.append("$maxDistance", radius)));
		if (categories != null) {
			query.put("properties.category", new BasicDBObject("$in", categories));
		}
		System.out.println(query.toString());
		DBCursor cursor = mapCol.find(query/* , new BasicDBObject("_id", 0) */);
		try {
			while (cursor.hasNext()) {
				JSONObject json = new JSONObject(cursor.next().toString());
				JSONObject properties = json.getJSONObject("properties");
				if ("ノード情報".equals(properties.get("category"))) {
					nodeMap.put(properties.getString("ノードID"), json);
				} else {
					features.add(json);
				}
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	@Override
	public String findNearestNode(double[] point, List<String> floors) {
		BasicDBObject query = new BasicDBObject().append("geometry", new BasicDBObject("$near",
				new BasicDBObject("$geometry", new BasicDBObject("type", "Point").append("coordinates", point))));
		query.put("properties.category", "ノード情報");
		if (floors != null) {
			query.put("properties.高さ", new BasicDBObject("$in", floors));
		}
		DBObject obj = mapCol.findOne(query, new BasicDBObject("_id", 0));
		if (obj instanceof DBObject) {
			DBObject properties = (DBObject) obj.get("properties");
			if (properties != null) {
				return (String) properties.get("ノードID");
			}
		}
		return null;
	}

	@Override
	public JSONObject find(String id) {
		return find(mapCol, id);
	}

	@Override
	public List<String> listFiles() {
		return mapCol.distinct("properties.file");
	}

	private JSONObject find(DBCollection col, String id) {
		try {
			DBObject obj = col.findOne(new BasicDBObject("_id", id));
			if (obj != null) {
				return new JSONObject(obj.toString());
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public JSONObject findUser(String id) {
		return find(userCol, id);
	}

	@Override
	public JSONArray listUsers() {
		JSONArray users = new JSONArray();
		DBCursor cursor = userCol.find();
		try {
			while (cursor.hasNext()) {
				users.add(new JSONObject(cursor.next().toString()));
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return users;
	}

	@Override
	public void insertUser(String json) {
		updateUser(json);
	}

	@Override
	public void updateUser(String json) {
		userCol.save((DBObject) com.mongodb.util.JSON.parse(json));
	}

	@Override
	public void removeUser(JSONObject obj) {
		try {
			if (obj.has("_id")) {
				userCol.remove(new BasicDBObject("_id", obj.getString("_id")));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void insertLog(String json) {
		insertLogList.add((DBObject) com.mongodb.util.JSON.parse(json));
		if (insertLogList.size() >= 1000) {
			flush();
		}
	}

	@Override
	public JSONArray getLogStats() {
		List<DBObject> pipeline = new ArrayList<DBObject>();
		pipeline.add(new BasicDBObject("$project", new BasicDBObject("client", 1).append("timestamp", "$timestamp")));
		pipeline.add(new BasicDBObject("$group",
				new BasicDBObject().append("_id", new BasicDBObject("client", "$client"))
						.append("count", new BasicDBObject("$sum", 1))
						.append("min", new BasicDBObject("$min", "$timestamp"))
						.append("max", new BasicDBObject("$max", "$timestamp"))));
		Iterator<DBObject> it = logCol.aggregate(pipeline).results().iterator();
		JSONArray result = new JSONArray();
		while (it.hasNext()) {
			try {
				DBObject elm = it.next();
				result.add(new JSONObject().put("clientId", ((DBObject) elm.get("_id")).get("client")).put("stats",
						new JSONObject().put("count", elm.get("count")).put("min", elm.get("min")).put("max",
								elm.get("max"))));
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		return result;
	}

	@Override
	public JSONArray getLogs(String clientId, String start, String end, String skip, String limit, String event) {
		BasicDBObject query = new BasicDBObject();
		if (clientId != null) {
			query.append("client", clientId);
		}

		new BasicDBObject("Date", new BasicDBObject("$gt", start).append("$lte", end));

		BasicDBObject timeRange = new BasicDBObject();
		if (start != null) {
			timeRange.append("$gte", Long.parseLong(start));
		}
		if (end != null) {
			timeRange.append("$lt", Long.parseLong(end));
		}
		if (timeRange.size() > 0) {
			query.append("timestamp", timeRange);
		}
		if (event != null) {
			query.append("event", event);
		}
		System.out.println(query.toString());
		DBCursor cursor = logCol.find(query);
		if (skip != null) {
			cursor = cursor.skip(Integer.parseInt(skip));
		}
		if (limit != null) {
			cursor = cursor.limit(Integer.parseInt(limit));
		}
		JSONArray result = new JSONArray();
		try {
			while (cursor.hasNext()) {
				result.add(new JSONObject(cursor.next().toString()));
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return result;
	}

	@Override
	public void saveAttachment(String path, InputStream is) {
		OutputStream os = getFileOutputStream(path);
		if (os != null) {
			try {
				byte data[] = new byte[16 * 1024];
				int len = 0;
				while ((len = is.read(data, 0, data.length)) > 0) {
					os.write(data, 0, len);
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {
					os.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Override
	public InputStream getAttachment(String path) {
		GridFSDBFile file = getFile(path);
		return file != null ? file.getInputStream() : null;
	}

	@Override
	public List<String> listAttachment() {
		List<String> files = new ArrayList<String>();
		for (DBCursor cursor = mFS.getFileList(); cursor.hasNext();) {
			DBObject obj = cursor.next();
			files.add(obj.get("filename").toString());
		}
		return files;
	}

	@Override
	public void deleteAttachment(String path) {
		if (getFile(path) != null) {
			mFS.remove(path);
		}
	}

	private GridFSDBFile getFile(String id) {
		return mFS.findOne(id);
	}

	private OutputStream getFileOutputStream(String id) {
		try {
			deleteAttachment(id);
			return mFS.createFile(id).getOutputStream();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public JSONObject getEntry(String id) {
		return find(entryCol, id);
	}

	@Override
	public void setEntry(JSONObject entry) {
		entryCol.save((DBObject) com.mongodb.util.JSON.parse(entry.toString()));
	}

	@Override
	public JSONArray getAgreements() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public JSONArray getAnswers(String deviceId) {
		// TODO Auto-generated method stub
		return null;
	}

}
