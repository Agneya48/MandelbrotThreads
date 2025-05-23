import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.GLAutoDrawable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * A GPU-accelerated display panel that mimics the function of the CPU-based panel, but renders
 * faster due to GPU assistance. Still laggy because of Swing limitations. WIP
 * Consider upgrading to GLCanvas instead for reduced latency, but more complex implementation
 * @author Josh Hampton hamptojt@mail.uc.edu
 */
public class MandelbrotPanelGPU extends GLJPanel implements GLEventListener {
    private long totalRenderTimeNs = 0;
    private int renderCount = 0;
    private double xMin = -2.0, xMax = 1.0, yMin = -1.5, yMax = 1.5;
    private int maxIter = 2000;
    private int width, height;
    private int colorMode = 0;
    private int paletteIndex = 1;
    private int renderScale = 1;
    private int dragStartX, dragStartY;

    private int shaderProgram;
    private int vertexShader;
    private int fragmentShader;
    private int vboID; // Stores GPU handle to the vertex buffer

    private GL4 gl4 = null;
    private boolean usingDoublePrecision;

    public MandelbrotPanelGPU() {
        super(new GLCapabilities(GLProfile.getDefault()));
        addGLEventListener(this);
        setPreferredSize(new Dimension(1000, 1000));

        setupMouseListeners();
    }

    //Helper method for sending Uniform coordinate 2d value to shaders, depending on gl version
    private void setUniformD(GL2 gl, String name, double x, double y) {
        int loc = gl.glGetUniformLocation(shaderProgram, name);
        if (gl4 != null) {
            gl4.glUniform2d(loc, x, y);
        } else {
            gl.glUniform2f(loc, (float) x, (float) y);
        }
    }

    //helper method for sending Uniform 1d value to shader, depending on gl version
    private void setUniformD(GL2 gl, String name, double value) {
        int loc = gl.glGetUniformLocation(shaderProgram, name);
        if (gl4 != null) {
            gl4.glUniform1d(loc, value);
        } else {
            gl.glUniform1f(loc, (float) value);
        }
    }

    /**
     * Creates gl objects and uploads fullscreen quad geometry to GPU
     */
    @Override
    public void init(GLAutoDrawable drawable) {
        //print Video card information to console for confirmation right GPU is being used
        GL glBase = drawable.getGL();
        GL2 gl = drawable.getGL().getGL2();

        if (glBase.isGL4()) {
            gl4 = drawable.getGL().getGL4();
            usingDoublePrecision = true;
        } else {
            usingDoublePrecision = false;
        }

        gl.glClearColor(0, 0, 0, 1);

        String vertexSource = loadShaderSource("mandelbrot.vert");

        String fragmentSource = loadShaderSource(
                usingDoublePrecision ? "mandelbrot_fp64.frag" : "mandelbrot.frag");

        if (vertexSource == null || fragmentSource == null) {
            System.err.println("Shader sources could not be loaded.");
            return;
        }

        vertexShader = gl.glCreateShader(GL2.GL_VERTEX_SHADER);
        fragmentShader = gl.glCreateShader(GL2.GL_FRAGMENT_SHADER);

        gl.glShaderSource(vertexShader, 1, new String[]{vertexSource}, null);
        gl.glCompileShader(vertexShader);
        checkShaderCompile(gl, vertexShader, "Vertex");

        gl.glShaderSource(fragmentShader, 1, new String[]{fragmentSource}, null);
        gl.glCompileShader(fragmentShader);
        checkShaderCompile(gl, fragmentShader, "Fragment");

        shaderProgram = gl.glCreateProgram();
        gl.glAttachShader(shaderProgram, vertexShader);
        gl.glAttachShader(shaderProgram, fragmentShader);
        gl.glLinkProgram(shaderProgram);
        checkProgramLink(gl, shaderProgram);

        //define quad geometry, identifies full screen rectangle space to render
        float[] quadVertices = {
                -1.0f, -1.0f,
                1.0f, -1.0f,
                -1.0f,  1.0f,
                1.0f,  1.0f
        };

        //Allocate GPU memory and upload vertex data
        FloatBuffer vertexBuffer = FloatBuffer.wrap(quadVertices);
        int[] buffers = new int[1];
        gl.glGenBuffers(1, buffers, 0);
        vboID = buffers[0];
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboID);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, quadVertices.length * Float.BYTES, vertexBuffer, GL.GL_STATIC_DRAW);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);

        System.out.printf(
                "GL_VERSION: %s\nGL_VENDOR: %s\nGL_RENDERER: %s\nusingDoublePrecision: %b\nshader: %s\n",
                glBase.glGetString(GL.GL_VERSION),
                glBase.glGetString(GL.GL_VENDOR),
                glBase.glGetString(GL.GL_RENDERER),
                usingDoublePrecision,
                usingDoublePrecision ? "fp64.frag" : "float.frag"
        );
    }

    private void checkShaderCompile(GL2 gl, int shader, String type) {
        int[] compiled = new int[1];
        gl.glGetShaderiv(shader, GL2.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            //if compile failed, output error log message to console
            System.err.println(type + " shader failed to compile:");
            int[] logLength = new int[1];
            gl.glGetShaderiv(shader, GL2.GL_INFO_LOG_LENGTH, logLength, 0);
            byte[] log = new byte[logLength[0]];
            gl.glGetShaderInfoLog(shader, logLength[0], (int[]) null, 0, log, 0);
            System.err.println(new String(log));
        }
    }

    private void checkProgramLink(GL2 gl, int program) {
        int[] linked = new int[1];
        gl.glGetProgramiv(program, GL2.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            System.err.println("Shader program failed to link:");
            int[] logLength = new int[1];
            gl.glGetProgramiv(program, GL2.GL_INFO_LOG_LENGTH, logLength, 0);
            byte[] log = new byte[logLength[0]];
            gl.glGetProgramInfoLog(program, logLength[0], (int[]) null, 0, log, 0);
            System.err.println(new String(log));
        }
    }

    /**
     *Cleans up memory management on panel or program close.
     */
    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL2 gl = drawable.getGL().getGL2();
        if (shaderProgram != 0) {
            gl.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        if (vboID != 0) {
            int[] buffers = { vboID };
            gl.glDeleteBuffers(1, buffers, 0);
            vboID = 0;
        }
        System.out.println("Cleaned up GPU resources.");
    }

    /**
     * Activates the shader and draws the defined quad area
     * Sends the uniform values to the shader, so correct position, zoom, palette, and renderMode are used
     * Very fast rendering, but currently limited by speed of Swing event dispatch, BufferedImage lag, and
     * GLJPanel updates. Switching to GLCanvas will resolve these issues at the cost of more complexity.
     */
    @Override
    public void display(GLAutoDrawable drawable) {
        long start = System.nanoTime(); //track time to render a frame when display changes
        GL2 gl = drawable.getGL().getGL2();
        gl.glClear(GL.GL_COLOR_BUFFER_BIT);
        gl.glUseProgram(shaderProgram); //use compiled Mandelbrot shader

        int resLoc = gl.glGetUniformLocation(shaderProgram, "u_resolution");
        gl.glUniform2f(resLoc, width, height);

        double cx = (xMin + xMax) / 2.0;
        double cy = (yMin + yMax) / 2.0;
        double zoom = xMax - xMin;

        setUniformD(gl, "u_center", cx, cy);
        setUniformD(gl, "u_zoom", zoom);

        int iterLoc = gl.glGetUniformLocation(shaderProgram, "u_maxIter");
        gl.glUniform1i(iterLoc, maxIter);

        int modeLoc = gl.glGetUniformLocation(shaderProgram, "colorMode");
        gl.glUniform1i(modeLoc, colorMode);

        int paletteLoc = gl.glGetUniformLocation(shaderProgram, "paletteMode");
        gl.glUniform1i(paletteLoc, paletteIndex);

        int posAttrib = gl.glGetAttribLocation(shaderProgram, "a_position");
        gl.glEnableVertexAttribArray(posAttrib);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboID);
        gl.glVertexAttribPointer(posAttrib, 2, GL.GL_FLOAT, false, 0, 0);
        gl.glDrawArrays(GL2.GL_TRIANGLE_STRIP, 0, 4);
        gl.glDisableVertexAttribArray(posAttrib);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        gl.glUseProgram(0);

        long end = System.nanoTime(); //now measure time elapsed
        double timeMs = (end - start) / 1_000_000.0;
        renderCount++;
        totalRenderTimeNs += (end - start);
        double avgTimeMs = (totalRenderTimeNs / 1_000_000.0) / renderCount;
        System.out.printf(
                "GPU render time: %.2f ms (Average over %d renders: %.2f ms)\n",
                timeMs, renderCount, avgTimeMs);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int w, int h) {
        GL2 gl = drawable.getGL().getGL2();
        gl.glViewport(0, 0, w, h);
        width = w;
        height = h;
    }

    public void saveImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save GPU Rendered Image");
        chooser.setSelectedFile(new File("mandelbrot_gpu.png"));
        int userSelection = chooser.showSaveDialog(this);

        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".png")) {
                file = new File(file.getAbsolutePath() + ".png");
            }

            int width = getWidth();
            int height = getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            this.paint(g2d);  // Renders the current view into the image
            g2d.dispose();

            try {
                ImageIO.write(image, "png", file);
                System.out.println("Saved image to: " + file.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Failed to save image:\n" + e.getMessage(),
                        "Save Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }


    public void resetView() {
        xMin = -2.0; xMax = 1.0;
        yMin = -1.5; yMax = 1.5;
        repaint();
    }

    public double[] getViewBounds() {
        return new double[]{xMin, xMax, yMin, yMax};
    }

    public void setViewBounds(double xMin, double xMax, double yMin, double yMax) {
        this.xMin = xMin;
        this.xMax = xMax;
        this.yMin = yMin;
        this.yMax = yMax;
        repaint();
    }

    public void setMaxIter(int iter) {
        this.maxIter = iter;
    }

    public void setColorMode(int mode) {
        this.colorMode = mode;
        repaint();
    }

    public void setRenderScale(int scale) {
        this.renderScale = scale;
        repaint();
    }

    private void setupMouseListeners() {
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStartX = e.getX();
                    dragStartY = e.getY();
                }
            }
        });

        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if ((e.getModifiersEx() & java.awt.event.MouseEvent.BUTTON1_DOWN_MASK) != 0) {
                    int dx = e.getX() - dragStartX;
                    int dy = e.getY() - dragStartY;

                    double dxFrac = (xMax - xMin) * dx / getWidth();
                    double dyFrac = (yMax - yMin) * dy / getHeight();

                    xMin -= dxFrac;
                    xMax -= dxFrac;
                    yMin += dyFrac; //inverted from JPanel
                    yMax += dyFrac; //also inverted

                    dragStartX = e.getX();
                    dragStartY = e.getY();
                    repaint();
                }
            }
        });

        addMouseWheelListener(e -> zoom(e.getX(), e.getY(), e.getWheelRotation() > 0 ? 1.2 : 0.8));
    }

    public void zoom(int px, int py, double scale) {
        double dx = xMax - xMin;
        double dy = yMax - yMin;
        double cx = xMin + dx * px / width;
        double cy = yMin + dy * (height - py) / height; //inverted from JPanel
        //subtle difference: OpenGL has ooordinate system origin from bottom left instead
        //of top left, so y values need to be inverted when the user is interacting with something

        dx *= scale;
        dy *= scale;

        xMin = cx - dx / 2.0;
        xMax = cx + dx / 2.0;
        yMin = cy - dy / 2.0;
        yMax = cy + dy / 2.0;

        repaint();
    }

    public String loadShaderSource(String filename) {
        try (InputStream input = getClass().getResourceAsStream(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            System.err.println("Failed to load shader: " + filename);
            e.printStackTrace();
            return null;
        }
    }

    public int getPaletteIndex() {
        return paletteIndex;
    }

    public void setPaletteIndex(int index) {
        this.paletteIndex = index;
        // Update any dependent palette uniforms here if needed
        repaint();
    }


}
