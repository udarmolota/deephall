package technology.rocketjump.mountaincore;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import technology.rocketjump.mountaincore.assets.ScaledTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes half-size copies of the biggest textures of the phone build, on the PC, after AssetsPackager has built
 * assets/ (see the phoneAssets task in desktop/build.gradle). Run with the staged phone game folder as the
 * working directory.
 *
 * <p>Copies the texture atlas pages (assets/tilesets, assets/ui/skin) and the main menu background when a side
 * is over {@link #MIN_SIDE_TO_SHRINK} and both sides are even, so the original size is exactly twice the copy's.
 * The originals stay: the copies go under {@link ScaledTextures#REDUCED_FOLDER} and are listed in
 * {@link ScaledTextures#SCALES_FILE}, and the game loads them only on phones the launcher asks it to (see
 * ScaledTextures). Only files the game loads through ScaledTextures may be listed here: anything else would
 * never be swapped for its copy.
 *
 * <p>Pixels are averaged 2x2 weighted by their alpha, so the colour of fully transparent pixels does not bleed
 * into the edges of sprites as dark fringes.
 */
public class PhoneTextureShrinker {

	private static final int MIN_SIDE_TO_SHRINK = 1024;
	private static final String[] ATLAS_FOLDERS = {"assets/tilesets", "assets/ui/skin"};
	private static final String[] SINGLE_TEXTURES = {"assets/main_menu/Dwarven Settlement.png"};

	public static void main(String[] args) throws IOException {
		List<String> candidates = new ArrayList<>();
		for (String folder : ATLAS_FOLDERS) {
			File[] files = new File(folder).listFiles((dir, name) -> name.endsWith(".png"));
			if (files != null) {
				for (File file : files) {
					candidates.add(folder + "/" + file.getName());
				}
			}
		}
		candidates.addAll(List.of(SINGLE_TEXTURES));

		JSONObject scales = new JSONObject(true);
		long full = 0, reduced = 0;
		for (String path : candidates) {
			File file = new File(path);
			if (!file.isFile()) {
				continue;
			}
			BufferedImage image = ImageIO.read(file);
			int width = image.getWidth(), height = image.getHeight();
			if (Math.max(width, height) <= MIN_SIDE_TO_SHRINK || width % 2 != 0 || height % 2 != 0) {
				continue;
			}
			String copyPath = ScaledTextures.REDUCED_FOLDER + path.substring("assets/".length());
			File copy = new File(copyPath);
			copy.getParentFile().mkdirs();
			BufferedImage half = halve(image);
			ImageIO.write(half, "png", copy);
			JSONObject entry = new JSONObject(true);
			entry.put("copy", copyPath);
			entry.put("scale", 2);
			entry.put("originalBytes", file.length());
			scales.put(path, entry);
			full += 4L * width * height;
			reduced += 4L * half.getWidth() * half.getHeight();
			System.out.println("Reduced copy of " + path + " " + width + "x" + height + " -> " + half.getWidth() + "x" + half.getHeight());
		}

		File scalesFile = new File(ScaledTextures.SCALES_FILE);
		scalesFile.getParentFile().mkdirs();
		Files.writeString(scalesFile.toPath(), JSONObject.toJSONString(scales, SerializerFeature.PrettyFormat), StandardCharsets.UTF_8);
		System.out.println("Reduced copies: " + scales.size() + ", decoded " + full / (1024 * 1024) + " MB at full size, " + reduced / (1024 * 1024) + " MB reduced");
	}

	private static BufferedImage halve(BufferedImage image) {
		int width = image.getWidth() / 2, height = image.getHeight() / 2;
		BufferedImage half = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] row0 = new int[image.getWidth()];
		int[] row1 = new int[image.getWidth()];
		int[] out = new int[width];
		for (int y = 0; y < height; y++) {
			image.getRGB(0, 2 * y, image.getWidth(), 1, row0, 0, image.getWidth());
			image.getRGB(0, 2 * y + 1, image.getWidth(), 1, row1, 0, image.getWidth());
			for (int x = 0; x < width; x++) {
				out[x] = average(row0[2 * x], row0[2 * x + 1], row1[2 * x], row1[2 * x + 1]);
			}
			half.setRGB(0, y, width, 1, out, 0, width);
		}
		return half;
	}

	private static int average(int... pixels) {
		long a = 0, r = 0, g = 0, b = 0;
		long plainR = 0, plainG = 0, plainB = 0;
		for (int p : pixels) {
			int pa = (p >>> 24) & 0xFF, pr = (p >>> 16) & 0xFF, pg = (p >>> 8) & 0xFF, pb = p & 0xFF;
			a += pa;
			r += (long) pr * pa;
			g += (long) pg * pa;
			b += (long) pb * pa;
			plainR += pr;
			plainG += pg;
			plainB += pb;
		}
		int outA = (int) ((a + 2) / 4);
		int outR, outG, outB;
		if (a == 0) {
			outR = (int) (plainR / 4);
			outG = (int) (plainG / 4);
			outB = (int) (plainB / 4);
		} else {
			outR = (int) ((r + a / 2) / a);
			outG = (int) ((g + a / 2) / a);
			outB = (int) ((b + a / 2) / a);
		}
		return (outA << 24) | (outR << 16) | (outG << 8) | outB;
	}
}
