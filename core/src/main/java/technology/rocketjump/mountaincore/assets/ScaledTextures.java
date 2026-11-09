package technology.rocketjump.mountaincore.assets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import org.pmw.tinylog.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Reduced copies of the biggest textures, for phones short of memory.
 *
 * <p>The game's textures come from the PC game and are large: the menu skin alone is 8192x8192, 256 MB once
 * decoded, and all of them together near 600 MB, more than a 4 GB phone can spare. When the phone build is
 * made, the biggest get a half-size copy under {@link #REDUCED_FOLDER} (PhoneTextureShrinker in the desktop
 * module), listed in {@link #SCALES_FILE}. The originals stay where they are.
 *
 * <p>The launcher decides which set a phone gets and says so with {@code -Ddeephall.textures=reduced}
 * (by default when the phone has less than 6 GB). Without it every texture loads at full size, as on the PC.
 *
 * <p>Everything in the game measures in pixels of the original texture: a sprite's size, a button's size from
 * its skin region, the place of each region in its atlas. So a reduced copy is loaded as it is, but reports
 * the original width and height to the rest of the game. OpenGL samples with coordinates from 0 to 1, so the
 * picture lands exactly where it did, only softer, in a quarter of the memory. Reading the small file also
 * makes loading faster and avoids decoding the full picture at all, which is what pushes a small phone over
 * the edge while the game loads.
 */
public final class ScaledTextures {

	public static final String SCALES_FILE = "assets/metadata/texture-scales.json";
	/** Where the reduced copies live, mirroring the paths of their originals under assets/. */
	public static final String REDUCED_FOLDER = "assets/reduced/";
	/** The system property the launcher sets, and the value that asks for the reduced set. */
	public static final String TEXTURES_PROPERTY = "deephall.textures";
	public static final String REDUCED = "reduced";

	private static Map<String, Reduced> reducedCopies;

	/**
	 * A reduced copy of an original texture. The original's size in bytes ties the copy to the file it was
	 * made from: after a mod change the phone rebuilds the atlases, and a copy of the old page would be wrong.
	 */
	private record Reduced(String copyPath, int scale, long originalBytes) {
	}

	private ScaledTextures() {
	}

	public static Texture texture(String path) {
		return texture(Gdx.files.internal(path));
	}

	public static Texture texture(FileHandle file) {
		Reduced reduced = reducedCopyOf(file);
		if (reduced == null) {
			return new Texture(file);
		}
		return new Texture(textureData(reduced, null, false));
	}

	/** A TextureAtlas whose reduced pages report their original size, so every region is cut where it always was. */
	public static TextureAtlas atlas(FileHandle atlasFile) {
		TextureAtlas.TextureAtlasData data = new TextureAtlas.TextureAtlasData(atlasFile, atlasFile.parent(), false);
		for (TextureAtlas.TextureAtlasData.Page page : data.getPages()) {
			Reduced reduced = reducedCopyOf(page.textureFile);
			if (reduced != null) {
				page.texture = new Texture(textureData(reduced, page.format, page.useMipMaps));
			}
		}
		return new TextureAtlas(data);
	}

	private static TextureData textureData(Reduced reduced, Pixmap.Format format, boolean useMipMaps) {
		FileHandle copy = Gdx.files.internal(reduced.copyPath());
		return new ScaledTextureData(new FileTextureData(copy, new Pixmap(copy), format, useMipMaps), reduced.scale());
	}

	/** The reduced copy to load in place of this file, or null to load the file itself. */
	private static Reduced reducedCopyOf(FileHandle file) {
		if (!REDUCED.equals(System.getProperty(TEXTURES_PROPERTY))) {
			return null;
		}
		Reduced reduced = reducedCopies().get(normalise(file.path()));
		if (reduced == null || reduced.scale() <= 1) {
			return null;
		}
		if (file.length() != reduced.originalBytes()) {
			// Rebuilt on the phone after a mod change: the copy shows the old picture, so the original it is
			Logger.warn("{} changed since its reduced copy was made, loading it at full size", file.path());
			return null;
		}
		if (!Gdx.files.internal(reduced.copyPath()).exists()) {
			Logger.warn("The reduced copy {} is missing, loading {} at full size", reduced.copyPath(), file.path());
			return null;
		}
		return reduced;
	}

	private static synchronized Map<String, Reduced> reducedCopies() {
		if (reducedCopies == null) {
			reducedCopies = new HashMap<>();
			FileHandle scalesFile = Gdx.files.internal(SCALES_FILE);
			if (scalesFile.exists()) {
				try {
					JSONObject json = JSON.parseObject(scalesFile.readString());
					for (String path : json.keySet()) {
						JSONObject entry = json.getJSONObject(path);
						reducedCopies.put(normalise(path), new Reduced(entry.getString("copy"),
								entry.getIntValue("scale"), entry.getLongValue("originalBytes")));
					}
					Logger.info("Loading the reduced copies of {} textures", reducedCopies.size());
				} catch (RuntimeException e) {
					Logger.error(e, "Could not read " + SCALES_FILE + ", loading textures at full size");
				}
			}
		}
		return reducedCopies;
	}

	// Paths in the list are relative to the game folder, as "assets/ui/skin/menu-skin.png"
	private static String normalise(String path) {
		String p = path.replace('\\', '/');
		while (p.startsWith("./")) {
			p = p.substring(2);
		}
		return p;
	}

	/** The reduced picture, with the original size reported to the game. */
	private static final class ScaledTextureData implements TextureData {
		private final FileTextureData data;
		private final int scale;

		ScaledTextureData(FileTextureData data, int scale) {
			this.data = data;
			this.scale = scale;
		}

		@Override
		public TextureDataType getType() {
			return data.getType();
		}

		@Override
		public boolean isPrepared() {
			return data.isPrepared();
		}

		@Override
		public void prepare() {
			data.prepare();
		}

		@Override
		public Pixmap consumePixmap() {
			return data.consumePixmap();
		}

		@Override
		public boolean disposePixmap() {
			return data.disposePixmap();
		}

		@Override
		public void consumeCustomData(int target) {
			data.consumeCustomData(target);
		}

		@Override
		public int getWidth() {
			return data.getWidth() * scale;
		}

		@Override
		public int getHeight() {
			return data.getHeight() * scale;
		}

		@Override
		public Pixmap.Format getFormat() {
			return data.getFormat();
		}

		@Override
		public boolean useMipMaps() {
			return data.useMipMaps();
		}

		@Override
		public boolean isManaged() {
			return data.isManaged();
		}
	}
}
