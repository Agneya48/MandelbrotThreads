import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.concurrent.*;

/**
 * A panel display that shows the Mandelbrot set, rendered with the CPU, in either multicore or
 * single-core mode. Allows a choice between various pallets and Mandelbrot calculation schemas.
 * @author Josh Hampton hamptojt@mail.uc.edu
 */
public class MandelbrotPanelMT extends JPanel {

    enum ColorMode { ESCAPE_TIME, SMOOTH, ORBIT_TRAP } // Different render modes for color calc
    private ColorMode colorMode = ColorMode.ESCAPE_TIME; //simplest for default

    private double xMin = -2.0, xMax = 1.0, yMin = -1.5, yMax = 1.5;
    private int maxIter = 2000;
    private Color baseColor = Color.BLUE;
    private Color[] palette = generateFirePalette();
    private int paletteIndex = 1;
    private BufferedImage image;
    private int renderScale = 1;
    private boolean boxZoomEnabled = false; //Note: disabled by default because box zoom not implemented yet
    private double totalRenderTimeMT = 0;
    private double totalRenderTimeST = 0;
    private int renderCountMT = 0;
    private int renderCountST = 0;
    private boolean autoRefine = true; //loads smaller resolution first, then refines, for smoother zooming
    private boolean showTimer = true;
    private int dragStartX, dragStartY; //for mouse clicking and dragging
    private boolean multithreaded = true;
    private boolean gpuEnabled = false;

    public MandelbrotPanelMT() {
        setBackground(Color.BLACK);
        setColorMode(0);
        setPalette(0);
        setupMouseListeners();
    }

    public void setColorMode(int index) {
        colorMode = ColorMode.values()[index];
        repaint();
    }

    public void chooseColor() {
        Color c = JColorChooser.showDialog(this, "Pick Base Color", baseColor);
        if (c != null) {
            baseColor = c; //JColorChooser will show last previously picked color
            palette = generatePalette(c);
            repaint();
        }
    }

    public void resetView() {
        xMin = -2.0; xMax = 1.0; yMin = -1.5; yMax = 1.5;
        repaint();
    }

    public void setRenderScale(int scale) {
        renderScale = scale;
        repaint();
    }

    public void setAutoRefine(boolean enabled) {
        autoRefine = enabled;
    }

    public void toggleTimer(boolean enabled) {
        showTimer = enabled;
    }

    //used for syncing zoom and position between CPU and GPU panels
    public double[] getViewBounds() {
        return new double[]{xMin, xMax, yMin, yMax};
    }

    //used for syncing zoom and position between CPU and GPU panels
    public void setViewBounds(double xMin, double xMax, double yMin, double yMax) {
        this.xMin = xMin;
        this.xMax = xMax;
        this.yMin = yMin;
        this.yMax = yMax;
        repaint();
    }

    public void saveImage() {
        if (image == null) return;
        JFileChooser fileChooser = new JFileChooser();
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File file = fileChooser.getSelectedFile();
                String name = file.getName();
                //add png extension if user forgets to type it, avoid some common issues with paths
                if (!name.toLowerCase().endsWith(".png")) {
                    file = new File(file.getParentFile(), name + ".png");
                }
                ImageIO.write(image, "png", file);
                JOptionPane.showMessageDialog(this, "Saved!");
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int width = getWidth() / renderScale;
        int height = getHeight() / renderScale;
        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        long start = System.nanoTime();

        if (gpuEnabled) {
            renderWithGPU(image, width, height);
        } else if (multithreaded) {
            renderMultiThreaded(image, width, height);
        } else {
            renderSingleThreaded(image, width, height);
        }

        long end = System.nanoTime();
        double time = (end - start) / 1_000_000.0;

        if (renderScale == 1 && showTimer) {
            if (multithreaded) {
                //System.out.printf("[Multithreaded] Render time: %.2f ms\n", time);
                totalRenderTimeMT += time;
                renderCountMT++;
                System.out.printf("[Multithreaded] Render time: %.2f ms (Average over %d renders: %.2f ms)\n",
                        time, renderCountMT, totalRenderTimeMT / renderCountMT);
            } else {
                //System.out.printf("[Single-threaded] Render time: %.2f ms\n", time);
                totalRenderTimeST += time;
                renderCountST++;
                System.out.printf("[Single-threaded] Render time: %.2f ms (Average over %d renders: %.2f ms)\n",
                        time, renderCountST, totalRenderTimeST / renderCountST);
            }
        }
        g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
    }



    public void setMultiThreaded(boolean enabled) {
        multithreaded = enabled;
    }

    private void renderMultiThreaded(BufferedImage image, int width, int height) {
        double[] bounds = normalizeAspectRatio(width, height);
        double xMinAdj = bounds[0], xMaxAdj = bounds[1], yMinAdj = bounds[2], yMaxAdj = bounds[3];

        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        CountDownLatch latch = new CountDownLatch(cores);

        for (int thread = 0; thread < cores; thread++) {
            int startY = thread * height / cores;
            int endY = (thread + 1) * height / cores;
            executor.submit(() -> {
                for (int y = startY; y < endY; y++) {
                    for (int x = 0; x < width; x++) {
                        double x0 = xMinAdj + x * (xMaxAdj - xMinAdj) / width;
                        double y0 = yMinAdj + y * (yMaxAdj - yMinAdj) / height;
                        // color each pixel based on current ColorMode setting
                        Color color = switch (colorMode) {
                            case ESCAPE_TIME -> getEscapeColor(x0, y0);
                                //int iter = mandelbrot(x0, y0);
                                //yield palette[iter % palette.length];
                            //smooths bands with logarithmic smoothing
                            case SMOOTH -> getSmoothColor(x0, y0);
                            //Very different, somewhat abstract and organic patterns
                            case ORBIT_TRAP -> orbitTrapColor(x0, y0);
                        };
                        image.setRGB(x, y, color.getRGB());
                    }
                }
                latch.countDown();
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        executor.shutdown();
    }

    private void renderSingleThreaded(BufferedImage image, int width, int height) {
        double[] bounds = normalizeAspectRatio(width, height);
        double xMinAdj = bounds[0], xMaxAdj = bounds[1], yMinAdj = bounds[2], yMaxAdj = bounds[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double x0 = xMinAdj + x * (xMaxAdj - xMinAdj) / width;
                double y0 = yMinAdj + y * (yMaxAdj - yMinAdj) / height;
                Color color = switch (colorMode) {
                    case ESCAPE_TIME -> getEscapeColor(x0, y0);
                        //classic Mandelbrot, produces sharp edges and banded concentric rings
                        //int iter = mandelbrot(x0, y0);
                        //yield palette[iter % palette.length];
                    case SMOOTH -> getSmoothColor(x0, y0);
                    case ORBIT_TRAP -> orbitTrapColor(x0, y0);
                };
                image.setRGB(x, y, color.getRGB());
            }
        }
    }

    public void setGpuEnabled(boolean enabled) {
        gpuEnabled = enabled;
    }

    private void renderWithGPU(BufferedImage image, int width, int height) {
        // Placeholder, actual GPU rendering occurs in the MandelbrotPanelGPU class
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.drawString("[GPU rendering not yet implemented]", 10, 20);
        g.dispose();
    }

    private int mandelbrot(double x0, double y0) {
        double x = 0, y = 0;
        int iter = 0;
        while (x * x + y * y <= 4 && iter < maxIter) {
            double xtemp = x * x - y * y + x0;
            y = 2 * x * y + y0;
            x = xtemp;
            iter++;
        }
        return iter;
    }

    private Color getEscapeColor(double x0, double y0) {
        int iter = mandelbrot(x0, y0);

        if (iter == maxIter) {
            return Color.BLACK;  // Inside the Mandelbrot set
        }

        // Wrap the index every 256 steps to simulate shader behavior
        int wrappedIndex = iter % 256;

        // Scale to match the palette length (assuming palette.length == 256)
        int index = (int) ((wrappedIndex / 255.0) * (palette.length - 1));

        return palette[iter % palette.length];
    }

    private Color getSmoothColor (double x0, double y0) {
        double x = 0, y = 0;
        int iter = 0;
        while (x * x + y * y <= 4 && iter < maxIter) {
            double xtemp = x * x - y * y + x0;
            y = 2 * x * y + y0;
            x = xtemp;
            iter++;
        }
        double zn = Math.sqrt(x * x + y * y); //calculates |z|, the magnitude of the final complex value
        //then estimates how far between two interations the point escaped
        double smooth = iter + 1 - Math.log(Math.log(zn)) / Math.log(2);
        int index = (int) (smooth * 5) % palette.length; //smooth factor * 5 can be adjusted
        return palette[Math.max(0, Math.min(index, palette.length - 1))];
    }

    private Color orbitTrapColor(double x0, double y0) {
        double x = x0, y = y0;
        double minDist = Double.MAX_VALUE;

        for (int i = 0; i < maxIter; i++) {
            double xtemp = x * x - y * y + x0;
            y = 2 * x * y + y0;
            x = xtemp;
            double dist = Math.sqrt(x * x + y * y);
            if (dist < minDist) minDist = dist;
            if (x * x + y * y > 100.0) break; // match the shader's escape threshold
        }

        double t = Math.exp(-minDist * 5.0);   // falloff multiplier matches shader
        t = Math.pow(t, 1.5);                  // gamma correction

        int index = (int) (t * (palette.length - 1));
        return palette[Math.max(0, Math.min(index, palette.length - 1))];
    }

    public void setPalette(int index) {
        paletteIndex = index;
        switch (index) {
            case 0 -> palette = generateGrayscalePalette();
            case 1 -> palette = generateOrangeBlackPalette();
            case 2 -> palette = generateCyanPalette();
            case 3 -> palette = generateBlueGreenPalette();
            case 4 -> palette = generateFirePalette();
            case 5 -> palette = generateHSV1Palette();
            case 6 -> palette = generateHSV2Palette();
            case 7 -> palette = generateHSV3Palette();
            default -> palette = generatePalette(baseColor);
        }
        repaint();
    }

    private Color[] generatePalette(Color base) {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            int r = (base.getRed() * i) / 255;
            int g = (base.getGreen() * i) / 255;
            int b = (base.getBlue() * i) / 255;
            colors[i] = new Color(r, g, b);
        }
        return colors;
    }

    private Color[] generateOrangeBlackPalette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float t = i / 255f;
            int r = (int) (t * 255);
            int g = (int) (t * 153);  // ~0.6 * 255
            int b = (int) (t * 51);   // ~0.2 * 255
            colors[i] = new Color(r, g, b);
        }
        return colors;
    }

    private Color[] generateCyanPalette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float t = i / 255f;
            int r = (int) (51 + t * 204);   // from 0.2 to 1.0
            int g = (int) (25 + t * 102);   // from 0.1 to 0.5
            int b = (int) (255 - t * 229);  // from 1.0 to ~0.1
            colors[i] = new Color(clamp(r), clamp(g), clamp(b));
        }
        return colors;
    }

    private Color[] generateBlueGreenPalette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float t = i / 255f;
            int r = (int) (25 + t * 51);    // 0.1 to ~0.3
            int g = (int) (t * 230);        // 0 to ~0.9
            int b = (int) (179 + t * 76);   // 0.7 to ~1.0
            colors[i] = new Color(clamp(r), clamp(g), clamp(b));
        }
        return colors;
    }

    private Color[] generateFirePalette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            double rFrac = Math.min(1.0, i / 85.0);
            double gFrac = Math.min(1.0, Math.max(0.0, (i - 85) / 85.0));
            double bFrac = Math.min(1.0, Math.max(0.0, (i - 170) / 85.0));
            int r = (int) (255 * rFrac);
            int g = (int) (255 * gFrac);
            int b = (int) (255 * bFrac);
            colors[i] = new Color(r, g, b);
        }
        return colors;
    }

    private Color[] generateHSV1Palette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float hue = (i / 256f);
            colors[i] = Color.getHSBColor(hue, 1f, 1f);
        }
        return colors;
    }

    private Color[] generateHSV2Palette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float hue = (float) Math.pow(i / 256f, 0.8);
            colors[i] = Color.getHSBColor(hue, 1f, 1f);
        }
        return colors;
    }

    private Color[] generateHSV3Palette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float hue = (float) Math.sqrt(i / 256f);
            colors[i] = Color.getHSBColor(hue, 1f, 1f);
        }
        return colors;
    }

    private Color[] generateCoolPalette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            float t = i / 255f;
            int r = (int)(64 + 128 * t);
            int g = (int)(32 + 64 * t);
            int b = (int)(128 + 127 * t);
            colors[i] = new Color(r, g, b);
        }
        return colors;
    }

    private Color[] generateGrayscalePalette() {
        Color[] colors = new Color[256];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = new Color(i, i, i);
        }
        return colors;
    }

    private void setupMouseListeners() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStartX = e.getX();
                    dragStartY = e.getY();
                }
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                    int dx = e.getX() - dragStartX;
                    int dy = e.getY() - dragStartY;

                    double dxFrac = (xMax - xMin) * dx / getWidth();
                    double dyFrac = (yMax - yMin) * dy / getHeight();

                    xMin -= dxFrac;
                    xMax -= dxFrac;
                    yMin -= dyFrac;
                    yMax -= dyFrac;

                    dragStartX = e.getX();
                    dragStartY = e.getY();
                    repaint();
                }
            }
        });

        addMouseWheelListener(e -> zoom(e.getX(), e.getY(), e.getWheelRotation() > 0 ? 1.2 : 0.8));
    }

    private void zoom(int px, int py, boolean zoomIn) {
        zoom(px, py, zoomIn ? 0.5 : 2.0);
    }

    private void zoom(int px, int py, double scale) {
        boolean isRefining = autoRefine && renderScale == 1;
        if (isRefining) { //first, load a low resolution preview image with a larger renderScale (stretched image)
            renderScale = 4;
        }
        double pixelThreshold = 1e-15;
        double pixelWidth = (xMax - xMin) * scale / getWidth();
        double pixelHeight = (yMax - yMin) * scale / getHeight();

        double cx = xMin + px * (xMax - xMin) / getWidth();
        double cy = yMin + py * (yMax - yMin) / getHeight();
        double newW = (xMax - xMin) * scale;
        double newH = (yMax - yMin) * scale;
        xMin = cx - newW / 2;
        xMax = cx + newW / 2;
        yMin = cy - newH / 2;
        yMax = cy + newH / 2;
        repaint();

        if (isRefining) {
            javax.swing.Timer timer = new javax.swing.Timer(200, e -> {
                renderScale = 1;
                repaint();
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    /**
     * Helper method for color generation
     */
    private int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }

    /**
     * Helper method to prevent stretching when window size changes, so
     * the JPanel display always matches the GPUPanel which automatically
     * normalizes based on vertical height
     */
    private double[] normalizeAspectRatio(int width, int height) {
        double aspectRatio = (double) width / height;
        double cx = (xMin + xMax) / 2;
        double cy = (yMin + yMax) / 2;
        double zoom = xMax - xMin;

        double newW = zoom;
        double newH = zoom / aspectRatio;

        return new double[]{cx - newW / 2, cx + newW / 2, cy - newH / 2, cy + newH / 2};
    }
}
