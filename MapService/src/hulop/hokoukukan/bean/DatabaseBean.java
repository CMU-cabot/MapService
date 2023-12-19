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
package hulop.hokoukukan.bean;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.wink.json4j.JSON;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import hulop.hokoukukan.servlet.FileFilter;
import hulop.hokoukukan.utils.CloudUtils;
import hulop.hokoukukan.utils.CloudantAdapter;
import hulop.hokoukukan.utils.DBAdapter;
import hulop.hokoukukan.utils.MongoAdapter;

public class DatabaseBean {
	public static final DBAdapter adapter = new hulop.hokoukukan.utils.COSAdapter(getDBAdapter());

	private static DBAdapter getDBAdapter() {
		JSONObject credentials = CloudUtils.getCredential(new String[] { "databases-for-mongodb" });
		if (credentials != null) {
			try {
				Object connection = credentials.get("connection");
				if (connection instanceof String) {
					connection = JSON.parse((String)connection);
				}
				JSONObject mongodb = ((JSONObject)connection).getJSONObject("mongodb");
				String url = mongodb.getJSONArray("composed").getString(0);
				String cert  = mongodb.getJSONObject("certificate").getString("certificate_base64");
				String dbName = System.getenv("HULOP_NAVI_DB");
				if (dbName == null) {
					dbName = "navi_db";
				}
				return new MongoAdapter(url, dbName, cert);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		String url = CloudUtils.getCredentialURL(new String[] { "cloudantNoSQLDB" }, null);
		if (url != null) {
			System.out.println(url);
			try {
				return new CloudantAdapter(url);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		url = CloudUtils.getCredential(new String[] { "compose-for-mongodb" }, "uri", null);
		if (url != null) {
			String cert = CloudUtils.getCredential(new String[] { "compose-for-mongodb" }, "ca_certificate_base64",
					null);
			if (cert != null) {
				String dbName = System.getenv("HULOP_NAVI_DB");
				if (dbName == null) {
					dbName = "navi_db";
				}
				System.out.println(url);
				System.out.println(dbName);
				System.out.println(cert);
				try {
					return new MongoAdapter(url, dbName, cert);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		url = CloudUtils.getCredentialURL(new String[] { "mongodb", "mongodb-2.4" },
				"mongodb://localhost:27017/navi_db");
		if (url != null) {
			System.out.println(url);
			try {
				return new MongoAdapter(url);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		throw new RuntimeException("No DB adapter");
	}

	public static void dropDatabase() {
		adapter.dropDB();
	}

	@Deprecated
	private static void importGML(InputStream is, File file) {
		System.err.println("importGML not supported");
	}

	@Deprecated
	private static void importNavcogJSON(InputStream is, File file) {
		System.err.println("importNavcogJSON not supported");
	}

	public static void importMapData(File zipFile, File file, String dataType) {
		final String dbDir = file.getName();
		InputStream is = null;
		try {
			is = new FileInputStream(zipFile);
			if ("gml.zip".equals(dataType)) {
				ZipInputStream zis = new ZipInputStream(is, Charset.forName(dbDir.contains("utf8") ? "UTF8" : "MS932"));
				for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
					String id = dbDir + "/" + entry.getName();
					if (!entry.isDirectory() && id.toLowerCase().endsWith(".gml")) {
						System.out.println("File: " + id);
						importGML(new BufferedInputStream(zis) {
							@Override
							public void close() throws IOException {
							}
						}, new File(id));
					}
				}
				zis.close();
			} else if ("navcog.json".equals(dataType)) {
				importNavcogJSON(is, file);
			} else if ("attachment.zip".equals(dataType)) {
				ZipInputStream zis = new ZipInputStream(is);
				for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
					String id = entry.getName();
					if (!entry.isDirectory()) {
						System.out.println("File: " + id);
						adapter.saveAttachment(id, new BufferedInputStream(zis) {
							@Override
							public void close() throws IOException {
							}
						});
					}
				}
				zis.close();
				FileFilter.onAttachmentChanged();
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static List<String> listFiles() {
		return adapter.listFiles();
	}

	public static List<String> listAttachment() {
		return adapter.listAttachment();
	}

	public static InputStream getAttachment(String path) {
		return adapter.getAttachment(path);
	}

	public static String readAttachment(String path) {
		InputStream is = getAttachment(path);
		if (is != null) {
			Reader reader = null;
			try {
				reader = new InputStreamReader(is);
				int length;
				char cbuf[] = new char[16 * 1024];
				StringBuilder sb = new StringBuilder();
				while ((length = reader.read(cbuf, 0, cbuf.length)) != -1) {
					sb.append(cbuf, 0, length);
				}
				return sb.toString();
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				if (reader != null) {
					try {
						reader.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
		return "";
	}

	public static void removeFile(File file) {
		adapter.prepare(file);
	}

	public static void deleteAttachment(String path) {
		if (path.endsWith("*")) {
			path = path.replace("*", "");
			for (String p : listAttachment()) {
				if (p.startsWith(path)) {
					adapter.deleteAttachment(p);
				}
			}
		} else {
			adapter.deleteAttachment(path);
		}
		FileFilter.onAttachmentChanged();
	}

	public static JSONArray insert(JSONArray array) {
		adapter.prepare(null);
		try {
			for (int i = 0; i < array.length(); i++) {
				adapter.insert(array.getString(i));
			}
			adapter.flush();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return adapter.getResult();
	}

	public static JSONArray update(JSONArray array) {
		adapter.prepare(null);
		try {
			for (int i = 0; i < array.length(); i++) {
				adapter.update(array.getString(i));
			}
			adapter.flush();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return adapter.getResult();
	}

	public static void remove(JSONArray array) {
		adapter.remove(array);
	}

	public static Object getToilets(double[] point, double distance) throws JSONException {
		return getFacilities(point, distance, DBAdapter.GeometryType.TOILETS);
	}

	public static Object getFacilities(double[] point, double distance, DBAdapter.GeometryType type) throws JSONException {
		RouteData rd = RouteData.getCache(point, distance);
		JSONArray features = rd.getFeatures();
		JSONObject siteMap = new JSONObject();
		JSONArray areaList = new JSONArray();
		for (Object feature : features) {
			try {
				JSONObject properties = ((JSONObject) feature).getJSONObject("properties");
				if (properties.has("facil_id")) {
					if (type == DBAdapter.GeometryType.TOILETS) {
						if (properties.getInt("facil_type") == 10) {
							switch (properties.getInt("toilet")) {
							case 3:
							case 4:
							case 5:
							case 6:
								siteMap.put(properties.getString("facil_id"), feature);
								break;
							}
						}
					} else {
						siteMap.put(properties.getString("facil_id"), feature);
					}
				} else if (properties.has("hulop_area_id")) {
					areaList.add(feature);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		JSONObject result = new JSONObject();
		result.put("siteMap", siteMap);
		result.put("areaList", areaList);
		return result;
	}

	private static boolean flushWaiting = false;
	private static Runnable flushWait = new Runnable() {
		@Override
		public void run() {
			try {
				Thread.sleep(1000);
				adapter.flush();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				flushWaiting = false;
			}
		}
	};

	public static void insertLogs(JSONArray array, HttpServletRequest request) {
		String user_agent = request.getHeader("User-Agent");
		if (user_agent != null) {
			for (Object obj : array) {
				try {
					((JSONObject) obj).put("user_agent", user_agent);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
		}
		try {
			for (int i = 0; i < array.length(); i++) {
				adapter.insertLog(array.getString(i));
			}
			if (!flushWaiting) {
				flushWaiting = true;
				new Thread(flushWait).start();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static JSONArray getLogStats(String event) {
		return adapter.getLogStats(event);
	}

	public static JSONArray getLogs(String clientId, String start, String end, String skip, String limit,
			String event) {
		JSONArray logs = adapter.getLogs(clientId, start, end, skip, limit, event);
		for (Object log : logs) {
			if (log instanceof JSONObject) {
				try {
					((JSONObject) log).remove("_id");
					((JSONObject) log).remove("_rev");

				} catch (Exception e) {
				}
			}
		}
		return logs;
	}

	public static JSONObject getEntry(String id) {
		return adapter.getEntry(id);
	}

	public static void setEntry(JSONObject entry, HttpServletRequest request) {
		String user_agent = request.getHeader("User-Agent");
		if (user_agent != null) {
			try {
				entry.put("user_agent", user_agent);
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
		adapter.setEntry(entry);
	}

	public static JSONArray getAgreements() {
		return adapter.getAgreements();
	}

	public static JSONArray getAnswers(String deviceId) {
		return adapter.getAnswers(deviceId);
	}

	public static void zipAttachments(HttpServletResponse response) throws IOException {
		response.setHeader("Content-Disposition", "attachment; filename=\"attachments.zip\"");
		ZipOutputStream zos = new ZipOutputStream(response.getOutputStream());
		try {
			byte data[] = new byte[1024 * 1024];
			int len;
			for (String path : listAttachment()) {
				InputStream is = getAttachment(path);
				if (is != null) {
					zos.putNextEntry(new ZipEntry(path));
					while ((len = is.read(data, 0, data.length)) > 0) {
						zos.write(data, 0, len);
					}
					zos.closeEntry();
					is.close();
				}
			}
		} finally {
			zos.close();
		}
	}

	public static void dumpLogs(OutputStream os) {
		adapter.dumpLogs(os);
	}

	public static void dumpEntries(OutputStream os) {
		adapter.dumpEntries(os);
	}

	public static void saveFile(InputStream is, String path) {
		adapter.saveAttachment(path, is);
		FileFilter.onAttachmentChanged();
	}

	public static void main(String[] args) {
		if (args.length > 0) {
			String dir = args[0];
			if (dir.endsWith(".zip")) {
				File zipFile = new File(dir);
				if (!zipFile.exists()) {
					System.err.println(dir + " does not exists");
					return;
				}
				importMapData(zipFile, zipFile, "gml.zip");
			}
		}
	}
}
