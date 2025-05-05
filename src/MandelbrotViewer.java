import javax.swing.*;

public class MandelbrotViewer extends JFrame {

    private MandelbrotPanelMT panel;
    private MandelbrotPanelGPU gpuPanel;
    private JComboBox<String> renderModeBox;
    private JComboBox<String> paletteBox;

    public MandelbrotViewer() {

        setTitle("Mandelbrot - Multithreaded");
        setSize(1100, 1000);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        //load panels. Default to Swing 2d JPanel for CPU rendering
        //fail gracefully if GPU panel fails to load
        panel = new MandelbrotPanelMT();
        gpuPanel = null;
        try {
            gpuPanel = new MandelbrotPanelGPU();
        } catch (Throwable t) {
            System.err.println("GPU panel failed to load: " + t.getMessage());
            gpuPanel = null;
        }
        panel.setAutoRefine(true);
        add(panel);

        JPanel controls = new JPanel();

        JButton colorBtn = new JButton("Color");
        colorBtn.addActionListener(e -> panel.chooseColor());

        JButton resetBtn = new JButton("Reset");
        resetBtn.addActionListener(e -> {
            int selectedMode = renderModeBox.getSelectedIndex();
            if (selectedMode == 2 && gpuPanel != null) {
                gpuPanel.resetView();
            } else {
                panel.resetView();
            }
        });

        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> {
            if (renderModeBox.getSelectedIndex() == 2 && gpuPanel != null) {
                gpuPanel.saveImage();
            } else {
                panel.saveImage();
            }
        });

        JCheckBox timerBox = new JCheckBox("Show Timer", true);
        timerBox.addActionListener(e -> panel.toggleTimer(timerBox.isSelected()));

        JComboBox<String> modeBox = new JComboBox<>(new String[]{"Escape Time", "Smooth", "Orbit Trap"});
        modeBox.addActionListener(e -> {
            panel.setColorMode(modeBox.getSelectedIndex());
            if (gpuPanel != null) {
                gpuPanel.setColorMode(modeBox.getSelectedIndex());
            }
        });

        String[] palettes = { "Grayscale", "Orange-Black", "Cyan", "Blue-Green", "Fire", "HSV1", "HSV2", "HSV3"};
        paletteBox = new JComboBox<>(palettes);

        paletteBox.addActionListener(e -> {
            int index = paletteBox.getSelectedIndex();
            panel.setPalette(index);
            if (gpuPanel != null) {
                gpuPanel.setPaletteIndex(index);
            }
            repaint();
        });
        paletteBox.setSelectedIndex(4); // default to fire coloring

        JLabel scaleLabel = new JLabel("Resolution Scale:");
        JSlider resolutionSlider = new JSlider(1, 4, 1); // 1x to 4x downscale
        resolutionSlider.setMajorTickSpacing(1);
        resolutionSlider.setPaintTicks(true);
        resolutionSlider.setPaintLabels(true);
        resolutionSlider.addChangeListener(e -> {
            panel.setRenderScale(resolutionSlider.getValue());
            if (gpuPanel != null) {
                gpuPanel.setRenderScale(resolutionSlider.getValue());
            }
        });

        JCheckBox refineBox = new JCheckBox("Auto-refine", true);
        refineBox.addActionListener(e -> {
            int selectedMode = renderModeBox.getSelectedIndex();
            if (selectedMode != 2) { // Only apply to non-GPU modes
                panel.setAutoRefine(refineBox.isSelected());
            }
        });

        JLabel renderLabel = new JLabel("Render Mode:");
        renderModeBox = new JComboBox<>(new String[]{
                "Multithreaded", "Single-threaded", "GPU (WIP)"});
        renderModeBox.addActionListener(e ->
                renderModeChange(renderModeBox.getSelectedIndex()));

        controls.add(colorBtn);
        controls.add(resetBtn);
        controls.add(saveBtn);
        controls.add(modeBox);
        controls.add(timerBox);
        controls.add(new JLabel("Palette:"));
        controls.add(paletteBox);
        //controls.add(scaleLabel); //Takes up too much space, disabled for now
        //controls.add(resolutionSlider); //replace with JComboBox
        controls.add(refineBox);
        controls.add(renderLabel);
        controls.add(renderModeBox);

        add(controls, "South");
        setVisible(true);
    }

    private void renderModeChange(int selected) {

        //sync zoom and position when changing modes
        double[] bounds = (selected == 2 && gpuPanel != null) ? panel.getViewBounds() : gpuPanel.getViewBounds();

        double xMin = bounds[0];
        double xMax = bounds[1];
        double yMin = bounds[2];
        double yMax = bounds[3];

        // Sync palettes
        int paletteIndex = paletteBox.getSelectedIndex();
        if (selected == 2) { // GPU mode
            gpuPanel.setPaletteIndex(paletteIndex);
        } else {
            panel.setPalette(paletteIndex);
        }

        if (selected == 2) { //GPU rendering selected
            if (gpuPanel == null) { // not supported, fail gracefully
                JOptionPane.showMessageDialog(this,
                        "GPU rendering is not supported or failed to load.\n Falling back to CPU rendering.",
                        "GPU error",
                        JOptionPane.WARNING_MESSAGE);
                panel.setGpuEnabled(false);
                panel.setMultiThreaded(true);
                renderModeBox.setSelectedIndex(0);
                panel.repaint();
            } else { //supported, replace CPU panel with GPU panel
                gpuPanel.setViewBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
                gpuPanel.setPaletteIndex(paletteIndex);
                SwingUtilities.invokeLater(() -> {
                    getContentPane().remove(panel);
                    getContentPane().add(gpuPanel);
                    revalidate();
                    repaint();
                });
            }
        } else { //CPU multithread or singlethread selected
            if (gpuPanel != null) { //switch from gpuPanel to cpu Panel
                panel.setViewBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
                panel.setPalette(paletteIndex);
                getContentPane().remove(gpuPanel);
            } else { //switching between multithread and singlethread
                panel.setViewBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
                panel.setPalette(paletteIndex);
            }
            getContentPane().add(panel);
            panel.setGpuEnabled(false);
            panel.setMultiThreaded(selected == 0); // 0 = multithread, 1 = singlethread
            panel.repaint();
            revalidate();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MandelbrotViewer::new);
    }
}
