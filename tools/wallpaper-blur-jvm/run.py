"""Verify the optimized wallpaper box blur is pixel-equivalent to the legacy truncated-window reference."""
from pathlib import Path
import os, subprocess, sys
root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/java/io/github/zerohomers0/onesteplite/systemui/chrome/HyperOsStyle.java').read_text('utf-8')
start = source.index('    private static void boxBlur(')
end = source.index('{', start) + 1
depth = 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
method = source[start:end]
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
java = r'''package io.github.zerohomers0.onesteplite;
public class WallpaperBlurRegression {
 static int checks;
 static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
 static final class Color {
  static int red(int c) { return (c >>> 16) & 255; }
  static int green(int c) { return (c >>> 8) & 255; }
  static int blue(int c) { return c & 255; }
  static int rgb(int r, int g, int b) { return 0xff000000 | (r << 16) | (g << 8) | b; }
 }
 static final class HyperOsStyle {
 BLUR_METHOD
 }
 static int sourcePixel(int x, int y) {
  return Color.rgb((x * 31 + y * 7) & 255, (x * 3 + y * 47) & 255, (x * 19 + y * 11) & 255);
 }
 static int[] reference(int[] source, int width, int height, int radius) {
  int[] horizontal = new int[source.length];
  for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
   int red = 0, green = 0, blue = 0, count = 0;
   for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
    int pixel = source[y * width + xx];
    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++;
   }
   horizontal[y * width + x] = Color.rgb(red / count, green / count, blue / count);
  }
  int[] result = new int[source.length];
  for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
   int red = 0, green = 0, blue = 0, count = 0;
   for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
    int pixel = horizontal[yy * width + x];
    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++;
   }
   result[y * width + x] = Color.rgb(red / count, green / count, blue / count);
  }
  return result;
 }
 public static void main(String[] args) {
  int width = 37, height = 17, radius = 4;
  int[] source = new int[width * height];
  for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
   source[y * width + x] = sourcePixel(x, y);
  int[] target = new int[source.length];
  HyperOsStyle.boxBlur(source, target, width, height, radius);
  int[] expected = reference(source, width, height, radius);
  int mismatches = 0, maxDelta = 0;
  for (int i = 0; i < expected.length; i++) {
   if (target[i] != expected[i]) mismatches++;
   maxDelta = Math.max(maxDelta, Math.abs(Color.red(target[i]) - Color.red(expected[i])));
   maxDelta = Math.max(maxDelta, Math.abs(Color.green(target[i]) - Color.green(expected[i])));
   maxDelta = Math.max(maxDelta, Math.abs(Color.blue(target[i]) - Color.blue(expected[i])));
  }
  check(mismatches == 0, "box blur mismatch pixels=" + mismatches + " maxDelta=" + maxDelta);
  System.out.println("Wallpaper box blur: " + checks + " checks passed");
 }
}'''.replace('BLUR_METHOD', method)
path = out / 'WallpaperBlurRegression.java'
path.write_text(java, 'utf-8')
jdk = Path(os.environ['ONESTEPLITE_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(path)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'io.github.zerohomers0.onesteplite.WallpaperBlurRegression'], check=True)
