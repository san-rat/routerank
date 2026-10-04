package lk.routerank.scoring;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local development: published files go to a folder (Vite serves it at /rankings), so the heatmap and
 * leaderboards work without R2.
 */
class FolderStore implements ObjectStore {

	private final Path root;

	FolderStore(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	@Override
	public void put(String key, byte[] body, String contentType, String cacheControl) {
		Path file = resolve(key);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = Files.createTempFile(file.getParent(), ".upload", ".tmp");
			Files.write(tmp, body);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Path resolve(String key) {
		Path file = root.resolve(key).normalize();
		if (!file.startsWith(root)) {
			throw new IllegalArgumentException("key outside the folder: " + key);
		}
		return file;
	}

}
