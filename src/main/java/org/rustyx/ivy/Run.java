package org.rustyx.ivy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.ivy.Ivy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class Run {
	private static Logger log = LoggerFactory.getLogger(Run.class);

	static class Ent {
		String path;
		String name;
		long size;
		Element el;
		String group;
		String artifact;
		String version;
		File source;
		@Override
		public String toString() {
			return "Ent [path=" + path + ", name=" + name + ", size=" + size + ", el=" + el + ", group=" + group + ", artifact=" + artifact
					+ ", version=" + version + ", source=" + source + "]";
		}
		
	}
	
	static final String mavenBaseUrl = "https://repo1.maven.org/maven2/";
	File classpathFile = new File(".classpath");
	File userhome = new File(System.getProperty("user.home"));
	File ivycache = new File(userhome, ".ivy2/cache");
	DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
	DocumentBuilder db; 
	Document classpathDoc;
	Map<String, Ent> jarPathMap = new TreeMap<>();
	Map<String, List<Ent>> jarNameMap = new TreeMap<>();
	Ivy ivy;
	String srcxml = "ivy-sources.xml";
	HttpClient httpClient;
	
	public Run() throws Exception {
		db = dbf.newDocumentBuilder();
		httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
               		.setSocketTimeout(3000).setConnectTimeout(3000)
               		.setCookieSpec(CookieSpecs.STANDARD).build())
                .setRetryHandler(new DefaultHttpRequestRetryHandler(2, true))
                .build();
	}
	
	public static void main(String[] args) {
		try {
			Run inst = new Run();
			inst.run();
		} catch (Throwable e) {
			log.error("Error", e);
		}

	}

	void run() throws Exception {
		readClasspath();
		findIvyArtifactsFromCache();
		findIvyArtifactsFromIvyConfig(new File("."));
		generateIvySourcesXml();
		resolveIvySources();
		findIvyArtifactsFromCache();
		findExistingSources();
		downloadMissingSources();
		updateClasspath();
		log.info("Finished");
	}

	void readClasspath() throws Exception {
		classpathDoc = db.parse(classpathFile);
		NodeList lst = classpathDoc.getElementsByTagName("classpathentry");
		for (int i = 0; i < lst.getLength(); ++i) {
			Element el = (Element)lst.item(i);
			String kind = el.getAttribute("kind");
			String path = el.getAttribute("path");
			String src = el.getAttribute("sourcepath");
			if ("classpathentry".equals(el.getTagName()) && path != null && "lib".equals(kind) && path.endsWith(".jar")) {
				File jarfile = new File(path);
				if (jarfile.exists()) {
					if (src != null || !new File(src).exists()) {
						Ent ent = new Ent();
						ent.path = path;
						ent.name = path.replaceFirst(".*/", "");
						ent.size = jarfile.length();
						ent.el = el;
						jarPathMap.put(path, ent);
						List<Ent> l = jarNameMap.get(ent.name);
						if (l == null) {
							l = new ArrayList<>();
							jarNameMap.put(ent.name, l);
						}
						l.add(ent);
					}
				}
			}
		}
	}
	
	void findIvyArtifactsFromIvyConfig(File dir) throws Exception {
		for (File f : dir.listFiles()) {
			if (f.isDirectory() && !(f.getName().equals(".") || f.getName().equals(".."))) {
				findIvyArtifactsFromIvyConfig(f);
			}
			if (f.isFile() && f.getName().matches("ivy.*\\.xml")) {
				try {
					Document d = db.parse(f);
					NodeList lst = d.getElementsByTagName("dependency");
					for (int i = 0; i < lst.getLength(); ++i) {
						Element el = (Element)lst.item(i);
						String org = el.getAttribute("org");
						String name = el.getAttribute("name");
						String rev = el.getAttribute("rev");
						if (org == null || name == null || rev == null)
							continue;
						String jar = name + "-" + rev + ".jar";
						List<Ent> l = jarNameMap.get(jar);
						if (l == null)
							continue;
						for (Ent e : l) {
							if (e.group == null) {
								e.group = org;
								e.artifact = name;
								e.version = rev;
							}
						}
					}					
				} catch (Exception e) {
					log.info("Unable to parse {}: {}", f, e.toString());
				}
			}
		}
		
	}

	void findIvyArtifactsFromCache() throws Exception {
		if (!ivycache.isDirectory())
			throw new IOException(ivycache.getAbsolutePath() + " is not a directory");
		for (File g : ivycache.listFiles()) {
			if (!g.isDirectory())
				continue;
			for (File a : g.listFiles()) {
				if (!a.isDirectory())
					continue;
				File jars = new File(a, "jars");
				if (!jars.isDirectory())
					continue;
				for (File jar : jars.listFiles()) {
					List<Ent> lst = jarNameMap.get(jar.getName());
					if (lst == null)
						continue;
					for (Ent ent : lst) {
						if (ent.size == jar.length()) {
							ent.group = g.getName();
							ent.artifact = a.getName();
							ent.version = StringUtils.substring(ent.name, ent.artifact.length() + 1, -4);
						}
					}
				}
				
			}
		}
	}

	void findExistingSources() throws Exception {
		for (Ent ent : jarPathMap.values()) {
			if (ent.version == null)
				continue;
			File src = new File(ivycache, ent.group + "/" + ent.artifact + "/sources/" + ent.artifact + "-" + ent.version + "-sources.jar");
			if (src.isFile()) {
				ent.source = src;
			}
		}
	}

	void downloadMissingSources() throws Exception {
		for (Ent ent : jarPathMap.values()) {
			if (ent.version == null || ent.source != null)
				continue;
			File src = new File(ivycache, ent.group + "/" + ent.artifact + "/sources/" + ent.artifact + "-" + ent.version + "-sources.jar");
			// Example: https://repo1.maven.org/maven2/javax/activation/activation/1.1/activation-1.1-sources.jar
			String uri = mavenBaseUrl + ent.group.replace('.', '/') + "/" + ent.artifact + "/" + ent.version + "/" + ent.artifact + '-' + ent.version + "-sources.jar";
			HttpGet get = new HttpGet(uri);
			HttpResponse res = httpClient.execute(get);
			StatusLine st = res.getStatusLine();
			HttpEntity entity = res.getEntity();
			if (st == null || st.getStatusCode() / 100 != 2 || entity == null) {
				log.info("Not found: {}", uri);
				EntityUtils.consumeQuietly(entity);
				continue;
			}
			src.getParentFile().mkdirs();
			try (FileOutputStream out = new FileOutputStream(src); InputStream in = entity.getContent()) {
				log.info("Downloading {} to {}", uri, src);
				IOUtils.copyLarge(in, out);
			}
			ent.source = src;
		}
	}

	void generateIvySourcesXml() throws Exception {
		try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(srcxml), "UTF-8"))) {
			out.println("<?xml version=\"1.0\" ?>");
			out.println("<ivy-module version=\"1.0\">");
			out.println("  <info organisation=\"example\" module=\"test\">");
			out.println("  </info>");
			out.println("  <dependencies>");
			for (Ent ent : jarPathMap.values()) {
				if (ent.version == null || ent.source != null)
					continue;
				out.println("    <dependency org=\"" + ent.group + "\" name=\"" + ent.artifact + "\" rev=\"" + ent.version + "\" />");
			}
			out.println("  </dependencies>");
			out.println("</ivy-module>");
		}
	}

	void resolveIvySources() throws Exception {
		ivy = Ivy.newInstance();
		ivy.configureDefault();
		ivy.getSettings().load(getClass().getResource("ivyconf.xml"));
		ivy.getSettings().load(getClass().getResource("ivysettings.xml"));
		ivy.resolve(new File(srcxml));
	}

	void updateClasspath() throws Exception {
		int updates = 0;
		for (Ent ent : jarPathMap.values()) {
			if (ent.version == null || ent.source == null)
				continue;
			ent.el.setAttribute("sourcepath", ent.source.getCanonicalPath().replace('\\', '/'));
			++updates;
		}
		if (updates > 0) {
			TransformerFactory tFactory = TransformerFactory.newInstance();
			Transformer transformer = tFactory.newTransformer();
			DOMSource source = new DOMSource(classpathDoc);
			log.info("Updating {}", classpathFile);
			StreamResult result = new StreamResult(classpathFile);
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.transform(source, result);
		}
	}


}
