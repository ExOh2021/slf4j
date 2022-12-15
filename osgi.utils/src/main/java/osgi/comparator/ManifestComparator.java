package osgi.comparator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;

import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.BundleException;

public class ManifestComparator {

	public static void main(String[] args) throws Exception {
		System.out.print("Load local Manifest ");
		Map<String, String> manifest;
		try {
			manifest = loadManifestMainSection(getArgument("bundle", args));
			System.out.print("Load baseline Manifest ");
		} catch (Exception e) {
			System.err.println("No artifact found: " + e.getMessage());
			return;
		}
		Set<String> ignoredHeaders = Set.of();
		try {
			ignoredHeaders = Set.of(getArgument("ignored.headers", args).split(","));
		} catch (Exception e) { // absent is ok
		}
		Map<String, String> baselineManifest = loadManifestMainSection(getArgument("baseline", args));
		compareMainAttributes(manifest, baselineManifest, ignoredHeaders);
	}

	private static boolean compareMainAttributes(Map<String, String> local, Map<String, String> baseline,
			Set<String> ignoredHeaders) throws BundleException {

		Set<String> commonHeaders = new HashSet<>(local.keySet());
		commonHeaders.retainAll(baseline.keySet());

		boolean difference = false;

		Map<String, List<String>> commonHeaderValueDifferences = new LinkedHashMap<>();
		for (String header : commonHeaders) {
			if (ignoredHeaders.contains(header)) {
				continue;
			}
			Set<String> localValues = getHeaderValues(header, local);
			Set<String> baselineValues = getHeaderValues(header, baseline);

			commonHeaderValueDifferences.put(header, List.of());

			if (!localValues.equals(baselineValues)) {

				BiFunction<Set<String>, Set<String>, List<String>> getNotInOther = (set, other) -> set.stream()
						.filter(v -> !other.contains(v)).sorted().toList();

				List<String> inLocalOnly = getNotInOther.apply(localValues, baselineValues);
				List<String> inBaselineOnly = getNotInOther.apply(baselineValues, localValues);

				if (!difference) {
					println("Common Headers with different values");
					difference = true;
				}
				println("  " + header);
				println("    Local   : " + String.join("\n              ", inLocalOnly));
				println("    Baseline: " + String.join("\n              ", inBaselineOnly));
			}
		}

		difference |= checkMissingHeader(local, commonHeaders, "baseline", ignoredHeaders);
		difference |= checkMissingHeader(baseline, commonHeaders, "local", ignoredHeaders);
		return difference;
	}

	private static Set<String> getHeaderValues(String header, Map<String, String> attributes) throws BundleException {
		ManifestElement[] elements = ManifestElement.parseHeader(header, attributes.get(header));
		return Arrays.stream(elements).map(ManifestElement::toString).collect(Collectors.toSet());
	}

	private static boolean checkMissingHeader(Map<String, String> attributes, Set<String> commonHeaders,
			String otherJar, Set<String> ignoredHeaders) {
		List<Entry<String, String>> missingInOther = new ArrayList<>();
		attributes.forEach((header, value) -> {
			if (!commonHeaders.contains(header) && !ignoredHeaders.contains(header)) {
				missingInOther.add(Map.entry(header, value));
			}
		});
		if (!missingInOther.isEmpty()) {
			println("Entries missing in " + otherJar + " jar");
			for (Entry<String, String> entry : missingInOther) {
				println("  " + entry.getKey() + ": " + entry.getValue());
			}
			return true;
		}
		return false;
	}

	private static String getArgument(String name, String[] args) {
		int argIndex = IntStream.range(0, args.length).filter(i -> ("-" + name).equals(args[i])).findFirst()
				.orElseThrow(() -> new NoSuchElementException("Argument not present: " + name));
		return args[argIndex + 1];
	}

	private static final Path MANIFEST_FILE = Paths.get(JarFile.MANIFEST_NAME);

	private static Map<String, String> loadManifestMainSection(String bundle) {
		Optional<Map<String, String>> manifest = Optional.empty();
		try {
			Path path = Path.of(bundle);
			manifest = manifest.or(() -> loadManifest(path));
			manifest = manifest.or(() -> loadManifest(path.resolve(MANIFEST_FILE)));
			manifest = manifest.or(() -> loadManifestFromJar(path));
		} catch (Exception e) { // not available
		}

		try {
			URL url = new URL(bundle);
			if (manifest.isEmpty() && Set.of("https", "http").contains(url.getProtocol())
					&& url.getPath().endsWith(".jar")) {

				String jarURL = "jar:" + url + "!/" + JarFile.MANIFEST_NAME;
				manifest = parseManifestMainSection(new URL(jarURL).openStream(), "jar '" + jarURL + "'");
			}
		} catch (Exception e) { // not available
		}
		return manifest.orElseThrow(() -> new NoSuchElementException("Not a bundle file path or URI: " + bundle));
	}

	private static Optional<Map<String, String>> loadManifest(Path path) {
		if (Files.isRegularFile(path) && path.endsWith(MANIFEST_FILE)) {
			String source = "file '" + path + "'";
			try {
				return parseManifestMainSection(Files.newInputStream(path), source);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load manifest from " + source, e);
			}
		}
		return Optional.empty();
	}

	private static Optional<Map<String, String>> loadManifestFromJar(Path path) {
		if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
			String source = "jar '" + path + "'";
			try (JarFile jar = new JarFile(path.toFile())) {
				ZipEntry manifestEntry = jar.getEntry(JarFile.MANIFEST_NAME);
				if (manifestEntry != null) {
					return parseManifestMainSection(jar.getInputStream(manifestEntry), source);
				}
			} catch (IOException e) {
				throw new IllegalStateException("Failed to load manifest from jar '" + source + "'", e);
			}
		}
		return Optional.empty();
	}

	private static Optional<Map<String, String>> parseManifestMainSection(InputStream stream, String source) {
		try (stream) {
			println("from " + source);
			return Optional.of(ManifestElement.parseBundleManifest(stream, new LinkedHashMap<>()));
		} catch (IOException | BundleException e) {
			throw new IllegalStateException("Failed to load manifest from " + source, e);
		}
	}

	private static void println(Object message) {
		System.out.println(message);
	}

}
