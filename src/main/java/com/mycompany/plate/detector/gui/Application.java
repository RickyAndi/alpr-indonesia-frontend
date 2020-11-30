/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.mycompany.plate.detector.gui;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.xml.transform.Result;

import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.MatOfByte;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.imgcodecs.Imgcodecs;


/**
 *
 * @author rickyandhi
 */
public class Application extends javax.swing.JFrame {

    private Optional<Integer> selectedStolenPlateId = Optional.ofNullable(null);
    private Optional<String> selectedStolenPlateString = Optional.ofNullable(null);

    private java.sql.Connection mysqlConnection = null;

    private Config config;

    private CardLayout mainPanelCardLayout;

    private Connection rabbitmqConnection;

    private final DefaultTableModel detectedPlateNumberTableModel = new DefaultTableModel(
            new Object [][] {},
            new String [] {
                    "Plat Nomor", "Curian?"
            }
    ) {
        final boolean[] canEdit = new boolean [] {
                false, false
        };

        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return canEdit [columnIndex];
        }
    };

    private Thread viewPanelRtspVideoThread = null;
    private Thread viewPanelQueueSubscriptionThread = null;

    private VideoCapture rtspCameraVideoCapture = null;

    /**
     * Creates new form Application
     */
    public Application(Config config) {

        try {
            Class.forName("com.mysql.jdbc.Driver");
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        this.config = config;
        
        initComponents();

        try {
            this.initRabbitMQConnection();
        } catch (Exception e) {
            System.out.println("Connection to rabbitmq server is not created");
        }

        try {
            this.initMysqlConnection();
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println("Connection to database cannot be created");
        }

        mainPanelCardLayout = (CardLayout) mainPanel.getLayout();

        this.initViewPanelCameraConnection();
        this.initViewRtspCameraThread();
        this.initViewPanelQueueSubscription();
        this.initDetectedPlateNumberModel();

        this.showLoadingPanelAndGoToViewPanel();
    }

    private void initMysqlConnection () throws SQLException {
        this.mysqlConnection = DriverManager
                .getConnection(this.config.getJdbcConnectionString());
    }

    private void initRabbitMQConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(this.config.getRabbitmqHost());
        factory.setPort(this.config.getRabbitmqPort());

        this.rabbitmqConnection = factory.newConnection();
    }

    private void initViewPanelQueueSubscription() {
        Application that = this;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Channel channel = that.rabbitmqConnection.createChannel();
                    channel.exchangeDeclare(
                            that.config.getRabbitmqExchangeName(),
                            that.config.getRabbitmqExchangeType()
                    );

                    String queueName = channel.queueDeclare().getQueue();

                    String routingName = that.config.getDetectedPlateNumberRoutingKey();

                    channel.queueBind(
                            queueName,
                            that.config.getRabbitmqExchangeName(),
                            routingName
                    );

                    DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                        String message = new String(delivery.getBody(), "UTF-8");
                        System.out.println(message);
                        JSONObject jsonObject = new JSONObject(message);
                        String detectedPlateNumber = jsonObject.getString("detected_plate_number");
                        Boolean isStolen = jsonObject.getBoolean("is_stolen");

                        that.addDetectedPlateNumberToTable(
                            detectedPlateNumber,
                            isStolen
                        );
                    };

                    channel.basicConsume(queueName, true, deliverCallback, consumerTag -> { });

                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }
            }
        });

        that.viewPanelQueueSubscriptionThread = thread;
    }

    private void initViewPanelCameraConnection() {
        String rtspUrl = this.config.getViewRtspCameraUrl();
        this.rtspCameraVideoCapture = new VideoCapture(rtspUrl);
    }

    private void showLoadingPanelAndGoToViewPanel() {
        Application that = this;

        ActionListener task = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                that.mainPanelCardLayout.show(mainPanel, "viewCardPanel");
            }
        };

        Timer timer = new Timer(3000, task);
        timer.setRepeats(false);
        timer.start();
    }

    public static BufferedImage Mat2BufferedImage(Mat mat) throws IOException{
        MatOfByte matOfByte = new MatOfByte();
        Imgcodecs.imencode(".jpg", mat, matOfByte);
        byte[] byteArray = matOfByte.toArray();
        InputStream in = new ByteArrayInputStream(byteArray);
        return ImageIO.read(in);
    }

    private void initDetectedPlateNumberModel() {
        detectedPlateNumberTable.setModel(this.detectedPlateNumberTableModel);

        detectedPlateNumberTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                Boolean isStolen = (Boolean) table.getModel().getValueAt(row, 1);

                if (isStolen) {
                    setBackground(Color.RED);
                    setForeground(Color.WHITE);
                } else {
                    setBackground(Color.GREEN);
                    setForeground(Color.WHITE);
                }
                return this;
            }
        });
    }

    private void initViewRtspCameraThread() {
        Application that = this;

        Thread thread = new Thread(new Runnable() {
            public void run() {

                Mat frame = new Mat();

                if (that.rtspCameraVideoCapture != null) {
                    while (that.rtspCameraVideoCapture.isOpened()) {

                        that.rtspCameraVideoCapture.read(frame);

                        Size sz = new Size(1100,600);
                        Mat resizedImage = new Mat();
                        Imgproc.resize( frame, resizedImage, sz );

                        try {

                            ImageIcon image = new ImageIcon(Mat2BufferedImage(resizedImage));
                            that.imageContainerLabel.setIcon(image);
                            that.imageContainerLabel.repaint();
                        } catch (Exception e) {
                            System.out.println(e.getMessage());
                        }
                    }
                } else {
                    System.out.println("Camera is not instantiated");
                }
            }
        });

        this.viewPanelRtspVideoThread = thread;
    }

    private void addDetectedPlateNumberToTable(String plateNumber, Boolean isStolen) {
        this.detectedPlateNumberTableModel.addRow(new Object[]{plateNumber, isStolen});
    }

    private void loadDataToStolenPlateTable() {
        try {
            PreparedStatement statement = null;

            if (!searchStolenPlateTextField.getText().equals("")) {
                statement = this.mysqlConnection
                        .prepareStatement("SELECT id, plate_number, created_at FROM stolen_plates WHERE plate_number LIKE ? ORDER BY created_at DESC");
                statement.setString(1, "%" + searchStolenPlateTextField.getText() + "%");
            } else {
                statement = this.mysqlConnection
                        .prepareStatement("SELECT id, plate_number, created_at FROM stolen_plates ORDER BY created_at DESC");
            }

            ResultSet result = statement.executeQuery();

            DefaultTableModel stolenPlateTableModel = (DefaultTableModel) stolenPlateTable.getModel();
            stolenPlateTableModel.setRowCount(0);

            while (result.next()) {
                stolenPlateTableModel.addRow(new Object[] {
                        result.getInt("id"),
                        result.getString("plate_number"),
                        result.getString("created_at")
                });
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    private void loadDataToDetectedStolenPlateTable() {

        DefaultTableModel detectedStolenTableModel = (DefaultTableModel) detectedStolenPlateTable.getModel();
        detectedStolenTableModel.setRowCount(0);

        String sql = "SELECT sp.plate_number, dsp.detected_time FROM detected_stolen_plates dsp " +
                "JOIN stolen_plates sp ON sp.id = dsp.stolen_plate_id";

        Boolean hasAfterDate = false;

        try {
            java.util.Date afterDate = detectedStolenPlateAfterDateInput.getDate();
            String afterDateString = new SimpleDateFormat("yyyy-MM-dd").format(afterDate);
            sql += " WHERE DATE(dsp.detected_time) > \"" + afterDateString + "\"";

            hasAfterDate = true;

        } catch (Exception e) {

        }

        try {
            java.util.Date beforeDate = detectedStolenPlateBeforeDateInput.getDate();
            String beforeDateString = new SimpleDateFormat("yyyy-MM-dd").format(beforeDate);

            if (hasAfterDate) {
                sql += " AND DATE(dsp.detected_time) < \"" + beforeDateString + "\"";
            } else {
                sql += " WHERE DATE(dsp.detected_time) < \"" + beforeDateString + "\"";
            }

        } catch (Exception e) {

        }

        System.out.println(sql);
        try {
            sql += " ORDER BY dsp.detected_time DESC";


            PreparedStatement statement = this.mysqlConnection
                    .prepareStatement(sql);
            ResultSet result = statement.executeQuery();
            System.out.println("hohoho");


            while (result.next()) {
                detectedStolenTableModel.addRow(new Object[] {
                    result.getString("plate_number"),
                    result.getString("detected_time")
                });
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        mainPanel = new javax.swing.JPanel();
        loadingPanel = new javax.swing.JPanel();
        jPanel1 = new javax.swing.JPanel();
        jLabel2 = new javax.swing.JLabel();
        jLabel3 = new javax.swing.JLabel();
        viewPanel = new javax.swing.JPanel();
        jPanel6 = new javax.swing.JPanel();
        jLabel4 = new javax.swing.JLabel();
        jScrollPane1 = new javax.swing.JScrollPane();
        detectedPlateNumberTable = new javax.swing.JTable();
        jPanel7 = new javax.swing.JPanel();
        imageContainerLabel = new javax.swing.JLabel();
        jLabel1 = new javax.swing.JLabel();
        historyPanel = new javax.swing.JPanel();
        jPanel10 = new javax.swing.JPanel();
        jLabel8 = new javax.swing.JLabel();
        jScrollPane4 = new javax.swing.JScrollPane();
        detectedStolenPlateTable = new javax.swing.JTable();
        detectedStolenPlateSearchButton = new javax.swing.JButton();
        jLabel10 = new javax.swing.JLabel();
        jLabel12 = new javax.swing.JLabel();
        detectedStolenPlateAfterDateInput = new com.toedter.calendar.JDateChooser();
        detectedStolenPlateBeforeDateInput = new com.toedter.calendar.JDateChooser();
        stolenPlateListPanel = new javax.swing.JPanel();
        jPanel8 = new javax.swing.JPanel();
        jLabel5 = new javax.swing.JLabel();
        jScrollPane3 = new javax.swing.JScrollPane();
        stolenPlateTable = new javax.swing.JTable();
        deleteStolenPlateButton = new javax.swing.JButton();
        searchStolenPlateTextField = new javax.swing.JTextField();
        searchStolenPlateList = new javax.swing.JButton();
        jLabel9 = new javax.swing.JLabel();
        jPanel9 = new javax.swing.JPanel();
        jLabel6 = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        toBeAddedPlateNumberTextField = new javax.swing.JTextField();
        addStolenPlateButton = new javax.swing.JButton();
        jPanel2 = new javax.swing.JPanel();
        jButton3 = new javax.swing.JButton();
        jButton6 = new javax.swing.JButton();
        jButton7 = new javax.swing.JButton();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        mainPanel.setBackground(java.awt.Color.white);
        mainPanel.setLayout(new java.awt.CardLayout());

        jPanel1.setBackground(java.awt.Color.white);
        jPanel1.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jLabel2.setIcon(new javax.swing.ImageIcon(getClass().getResource("/Pulse-1s-200px.gif"))); // NOI18N

        jLabel3.setFont(new java.awt.Font("Ubuntu", 1, 36)); // NOI18N
        jLabel3.setText("Sedang Memuat ........");

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addContainerGap(501, Short.MAX_VALUE)
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addComponent(jLabel2)
                        .addGap(559, 559, 559))
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addComponent(jLabel3)
                        .addGap(469, 469, 469))))
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addGap(206, 206, 206)
                .addComponent(jLabel2)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jLabel3)
                .addContainerGap(108, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout loadingPanelLayout = new javax.swing.GroupLayout(loadingPanel);
        loadingPanel.setLayout(loadingPanelLayout);
        loadingPanelLayout.setHorizontalGroup(
            loadingPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        loadingPanelLayout.setVerticalGroup(
            loadingPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel1, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );

        mainPanel.add(loadingPanel, "card5");

        viewPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentShown(java.awt.event.ComponentEvent evt) {
                viewPanelComponentShown(evt);
            }
            public void componentHidden(java.awt.event.ComponentEvent evt) {
                viewPanelComponentHidden(evt);
            }
        });

        jPanel6.setBackground(java.awt.Color.white);
        jPanel6.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jLabel4.setFont(new java.awt.Font("Ubuntu", 1, 24)); // NOI18N
        jLabel4.setText("Daftar deteksi plat nomor");

        jScrollPane1.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        detectedPlateNumberTable.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));
        detectedPlateNumberTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {
                {null, null},
                {null, null},
                {null, null},
                {null, null}
            },
            new String [] {
                "Plat nomor", "Curian?"
            }
        ) {
            boolean[] canEdit = new boolean [] {
                false, false
            };

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        jScrollPane1.setViewportView(detectedPlateNumberTable);

        javax.swing.GroupLayout jPanel6Layout = new javax.swing.GroupLayout(jPanel6);
        jPanel6.setLayout(jPanel6Layout);
        jPanel6Layout.setHorizontalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(jLabel4)
                        .addGap(0, 0, Short.MAX_VALUE))
                    .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 486, Short.MAX_VALUE))
                .addContainerGap())
        );
        jPanel6Layout.setVerticalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel6Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel4)
                .addGap(18, 18, 18)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 481, Short.MAX_VALUE)
                .addContainerGap())
        );

        jPanel7.setBackground(java.awt.Color.white);
        jPanel7.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        imageContainerLabel.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jLabel1.setFont(new java.awt.Font("Ubuntu", 1, 24)); // NOI18N
        jLabel1.setText("Tampilan Kamera");

        javax.swing.GroupLayout jPanel7Layout = new javax.swing.GroupLayout(jPanel7);
        jPanel7.setLayout(jPanel7Layout);
        jPanel7Layout.setHorizontalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(jLabel1)
                        .addGap(0, 570, Short.MAX_VALUE))
                    .addComponent(imageContainerLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        jPanel7Layout.setVerticalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel1)
                .addGap(18, 18, 18)
                .addComponent(imageContainerLabel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        javax.swing.GroupLayout viewPanelLayout = new javax.swing.GroupLayout(viewPanel);
        viewPanel.setLayout(viewPanelLayout);
        viewPanelLayout.setHorizontalGroup(
            viewPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(viewPanelLayout.createSequentialGroup()
                .addComponent(jPanel6, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGap(18, 18, 18)
                .addComponent(jPanel7, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        viewPanelLayout.setVerticalGroup(
            viewPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(viewPanelLayout.createSequentialGroup()
                .addGroup(viewPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel6, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanel7, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );

        mainPanel.add(viewPanel, "viewCardPanel");

        historyPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentShown(java.awt.event.ComponentEvent evt) {
                historyPanelComponentShown(evt);
            }
        });

        jPanel10.setBackground(java.awt.Color.white);
        jPanel10.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jLabel8.setFont(new java.awt.Font("Ubuntu", 1, 24)); // NOI18N
        jLabel8.setText("Plat Curian Terdeteksi");

        jScrollPane4.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        detectedStolenPlateTable.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));
        detectedStolenPlateTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "Plat Nomor", "Tanggal Terdeteksi"
            }
        ));
        jScrollPane4.setViewportView(detectedStolenPlateTable);
        if (detectedStolenPlateTable.getColumnModel().getColumnCount() > 0) {
            detectedStolenPlateTable.getColumnModel().getColumn(0).setResizable(false);
            detectedStolenPlateTable.getColumnModel().getColumn(1).setResizable(false);
        }

        detectedStolenPlateSearchButton.setText("Cari");
        detectedStolenPlateSearchButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                detectedStolenPlateSearchButtonActionPerformed(evt);
            }
        });

        jLabel10.setFont(new java.awt.Font("Ubuntu", 0, 18)); // NOI18N
        jLabel10.setText("Tanggal Sesudah");

        jLabel12.setFont(new java.awt.Font("Ubuntu", 0, 18)); // NOI18N
        jLabel12.setText("Tanggal Sebelum");

        javax.swing.GroupLayout jPanel10Layout = new javax.swing.GroupLayout(jPanel10);
        jPanel10.setLayout(jPanel10Layout);
        jPanel10Layout.setHorizontalGroup(
            jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel10Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel8)
                    .addGroup(jPanel10Layout.createSequentialGroup()
                        .addGap(6, 6, 6)
                        .addGroup(jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                            .addComponent(jScrollPane4, javax.swing.GroupLayout.PREFERRED_SIZE, 667, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addGroup(jPanel10Layout.createSequentialGroup()
                                .addGroup(jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                                    .addComponent(detectedStolenPlateAfterDateInput, javax.swing.GroupLayout.PREFERRED_SIZE, 243, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(jLabel10))
                                .addGap(28, 28, 28)
                                .addGroup(jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                                    .addComponent(jLabel12)
                                    .addComponent(detectedStolenPlateBeforeDateInput, javax.swing.GroupLayout.PREFERRED_SIZE, 227, javax.swing.GroupLayout.PREFERRED_SIZE))
                                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                                .addComponent(detectedStolenPlateSearchButton, javax.swing.GroupLayout.PREFERRED_SIZE, 120, javax.swing.GroupLayout.PREFERRED_SIZE)))))
                .addContainerGap(642, Short.MAX_VALUE))
        );
        jPanel10Layout.setVerticalGroup(
            jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel10Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel8)
                .addGap(18, 18, 18)
                .addGroup(jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel10)
                    .addComponent(jLabel12))
                .addGap(18, 18, 18)
                .addGroup(jPanel10Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(detectedStolenPlateSearchButton, javax.swing.GroupLayout.DEFAULT_SIZE, 53, Short.MAX_VALUE)
                    .addComponent(detectedStolenPlateAfterDateInput, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(detectedStolenPlateBeforeDateInput, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addGap(18, 18, 18)
                .addComponent(jScrollPane4, javax.swing.GroupLayout.DEFAULT_SIZE, 371, Short.MAX_VALUE)
                .addContainerGap())
        );

        javax.swing.GroupLayout historyPanelLayout = new javax.swing.GroupLayout(historyPanel);
        historyPanel.setLayout(historyPanelLayout);
        historyPanelLayout.setHorizontalGroup(
            historyPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel10, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        historyPanelLayout.setVerticalGroup(
            historyPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(historyPanelLayout.createSequentialGroup()
                .addComponent(jPanel10, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );

        mainPanel.add(historyPanel, "historyCardPanel");

        stolenPlateListPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentShown(java.awt.event.ComponentEvent evt) {
                stolenPlateListPanelComponentShown(evt);
            }
        });

        jPanel8.setBackground(java.awt.Color.white);
        jPanel8.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jLabel5.setFont(new java.awt.Font("Ubuntu", 1, 24)); // NOI18N
        jLabel5.setText("Daftar Nomor Plat Curian");

        jScrollPane3.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        stolenPlateTable.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));
        stolenPlateTable.setModel(new javax.swing.table.DefaultTableModel(
            new Object [][] {

            },
            new String [] {
                "ID", "Plat Nomor", "Tanggal ditambahkan"
            }
        ) {
            Class[] types = new Class [] {
                java.lang.Integer.class, java.lang.String.class, java.lang.String.class
            };
            boolean[] canEdit = new boolean [] {
                false, false, false
            };

            public Class getColumnClass(int columnIndex) {
                return types [columnIndex];
            }

            public boolean isCellEditable(int rowIndex, int columnIndex) {
                return canEdit [columnIndex];
            }
        });
        stolenPlateTable.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                stolenPlateTableMouseClicked(evt);
            }
        });
        jScrollPane3.setViewportView(stolenPlateTable);

        deleteStolenPlateButton.setBackground(new java.awt.Color(250, 9, 9));
        deleteStolenPlateButton.setText("Hapus");
        deleteStolenPlateButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deleteStolenPlateButtonActionPerformed(evt);
            }
        });

        searchStolenPlateList.setText("Cari");
        searchStolenPlateList.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                searchStolenPlateListActionPerformed(evt);
            }
        });

        jLabel9.setFont(new java.awt.Font("Ubuntu", 0, 18)); // NOI18N
        jLabel9.setText("Cari Berdasar Plat Nomor");

        javax.swing.GroupLayout jPanel8Layout = new javax.swing.GroupLayout(jPanel8);
        jPanel8.setLayout(jPanel8Layout);
        jPanel8Layout.setHorizontalGroup(
            jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel8Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane3, javax.swing.GroupLayout.DEFAULT_SIZE, 600, Short.MAX_VALUE)
                    .addGroup(jPanel8Layout.createSequentialGroup()
                        .addComponent(searchStolenPlateTextField)
                        .addGap(18, 18, 18)
                        .addComponent(searchStolenPlateList, javax.swing.GroupLayout.PREFERRED_SIZE, 123, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanel8Layout.createSequentialGroup()
                        .addGroup(jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel5)
                            .addComponent(jLabel9)
                            .addComponent(deleteStolenPlateButton, javax.swing.GroupLayout.PREFERRED_SIZE, 149, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 0, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanel8Layout.setVerticalGroup(
            jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel8Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel5)
                .addGap(18, 18, 18)
                .addComponent(jLabel9)
                .addGap(18, 18, 18)
                .addGroup(jPanel8Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(searchStolenPlateTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(searchStolenPlateList, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(18, 18, 18)
                .addComponent(jScrollPane3, javax.swing.GroupLayout.DEFAULT_SIZE, 309, Short.MAX_VALUE)
                .addGap(18, 18, 18)
                .addComponent(deleteStolenPlateButton, javax.swing.GroupLayout.PREFERRED_SIZE, 44, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        jPanel9.setBackground(java.awt.Color.white);
        jPanel9.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jLabel6.setBackground(java.awt.Color.white);
        jLabel6.setFont(new java.awt.Font("Ubuntu", 1, 24)); // NOI18N
        jLabel6.setText("Tambahkan Plat Nomor");

        jLabel7.setFont(new java.awt.Font("Ubuntu", 0, 18)); // NOI18N
        jLabel7.setText("Plat nomor");

        addStolenPlateButton.setText("Simpan plat nomor");
        addStolenPlateButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addStolenPlateButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel9Layout = new javax.swing.GroupLayout(jPanel9);
        jPanel9.setLayout(jPanel9Layout);
        jPanel9Layout.setHorizontalGroup(
            jPanel9Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel9Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel9Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(toBeAddedPlateNumberTextField)
                    .addGroup(jPanel9Layout.createSequentialGroup()
                        .addGroup(jPanel9Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel6)
                            .addComponent(jLabel7)
                            .addComponent(addStolenPlateButton, javax.swing.GroupLayout.PREFERRED_SIZE, 203, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(0, 385, Short.MAX_VALUE)))
                .addContainerGap())
        );
        jPanel9Layout.setVerticalGroup(
            jPanel9Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel9Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel6)
                .addGap(18, 18, 18)
                .addComponent(jLabel7)
                .addGap(18, 18, 18)
                .addComponent(toBeAddedPlateNumberTextField, javax.swing.GroupLayout.PREFERRED_SIZE, 52, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(addStolenPlateButton, javax.swing.GroupLayout.PREFERRED_SIZE, 44, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout stolenPlateListPanelLayout = new javax.swing.GroupLayout(stolenPlateListPanel);
        stolenPlateListPanel.setLayout(stolenPlateListPanelLayout);
        stolenPlateListPanelLayout.setHorizontalGroup(
            stolenPlateListPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(stolenPlateListPanelLayout.createSequentialGroup()
                .addComponent(jPanel8, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jPanel9, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        stolenPlateListPanelLayout.setVerticalGroup(
            stolenPlateListPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(stolenPlateListPanelLayout.createSequentialGroup()
                .addGroup(stolenPlateListPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel8, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanel9, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );

        mainPanel.add(stolenPlateListPanel, "stolenPlateListCardPanel");

        jPanel2.setBackground(java.awt.Color.white);
        jPanel2.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 0, 0)));

        jButton3.setText("Terdeteksi");
        jButton3.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton3ActionPerformed(evt);
            }
        });

        jButton6.setText(" Plat Nomor Curian");
        jButton6.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton6ActionPerformed(evt);
            }
        });

        jButton7.setText("Pengawasan");
        jButton7.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton7ActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jButton7, javax.swing.GroupLayout.PREFERRED_SIZE, 115, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jButton6, javax.swing.GroupLayout.PREFERRED_SIZE, 163, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18)
                .addComponent(jButton3, javax.swing.GroupLayout.PREFERRED_SIZE, 148, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(jButton7, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jButton3, javax.swing.GroupLayout.PREFERRED_SIZE, 87, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jButton6, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(mainPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(jPanel2, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(mainPanel, javax.swing.GroupLayout.PREFERRED_SIZE, 565, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void viewPanelComponentShown(java.awt.event.ComponentEvent evt) {//GEN-FIRST:event_viewPanelComponentShown
        this.viewPanelQueueSubscriptionThread.start();
        this.viewPanelRtspVideoThread.start();
    }//GEN-LAST:event_viewPanelComponentShown

    private void viewPanelComponentHidden(java.awt.event.ComponentEvent evt) {//GEN-FIRST:event_viewPanelComponentHidden
        this.viewPanelQueueSubscriptionThread.interrupt();
        this.viewPanelRtspVideoThread.interrupt();
    }//GEN-LAST:event_viewPanelComponentHidden

    private void jButton7ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton7ActionPerformed
        this.mainPanelCardLayout.show(mainPanel, "viewCardPanel");
    }//GEN-LAST:event_jButton7ActionPerformed

    private void jButton6ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton6ActionPerformed
        this.mainPanelCardLayout.show(mainPanel, "stolenPlateListCardPanel");
    }//GEN-LAST:event_jButton6ActionPerformed

    private void jButton3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton3ActionPerformed
        this.mainPanelCardLayout.show(mainPanel, "historyCardPanel");
    }//GEN-LAST:event_jButton3ActionPerformed

    private void stolenPlateListPanelComponentShown(java.awt.event.ComponentEvent evt) {//GEN-FIRST:event_stolenPlateListPanelComponentShown
       this.loadDataToStolenPlateTable();
    }//GEN-LAST:event_stolenPlateListPanelComponentShown

    private void historyPanelComponentShown(java.awt.event.ComponentEvent evt) {//GEN-FIRST:event_historyPanelComponentShown
        this.loadDataToDetectedStolenPlateTable();
    }//GEN-LAST:event_historyPanelComponentShown

    private void addStolenPlateButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addStolenPlateButtonActionPerformed

        String plateNumberToBeAdded = toBeAddedPlateNumberTextField.getText();

        if (plateNumberToBeAdded.equals("")) {
            JOptionPane.showMessageDialog(this, "Mohon isi plat nomor yang akan ditambahkan");
        } else {

            if (this.selectedStolenPlateId.isPresent()) {
                Integer selectedStolenPlateId = this.selectedStolenPlateId.get();
                String oldPlateNumber = this.selectedStolenPlateString.get();
                String newPlateNumber = toBeAddedPlateNumberTextField.getText();
                if (!oldPlateNumber.equals(newPlateNumber)) {
                    String question = "Apakah anda akan mengubah plat " + oldPlateNumber + " Menjadi " + newPlateNumber;
                    Integer answer = JOptionPane.showConfirmDialog(this, question);
                    if (answer == 0) {
                        try {
                            String sql = "SELECT id FROM stolen_plates WHERE plate_number = ? AND id != ?";
                            PreparedStatement statement = this.mysqlConnection.prepareStatement(sql);
                            statement.setString(1, newPlateNumber);
                            statement.setInt(2, selectedStolenPlateId);
                            ResultSet result = statement.executeQuery();

                            if (result.next()) {
                                JOptionPane.showMessageDialog(this, "Plat nomor yang baru telah digunakan, mohon mengubah dengan plat nomor lain");
                            } else {
                                String updateSql = "UPDATE stolen_plates SET plate_number = ? WHERE id = ?";
                                PreparedStatement updateStatement = this.mysqlConnection.prepareStatement(updateSql);
                                updateStatement.setString(1, newPlateNumber);
                                updateStatement.setInt(2, selectedStolenPlateId);
                                updateStatement.execute();

                                this.selectedStolenPlateId = Optional.ofNullable(null);
                                this.selectedStolenPlateString = Optional.ofNullable(null);

                                this.loadDataToStolenPlateTable();

                                JOptionPane.showMessageDialog(this, "Plat nomor telah berhasil diubah");

                                stolenPlateTable.clearSelection();
                            }
                        } catch (Exception e) {

                            stolenPlateTable.clearSelection();

                            this.selectedStolenPlateId = Optional.ofNullable(null);
                            this.selectedStolenPlateString = Optional.ofNullable(null);

                            JOptionPane.showMessageDialog(this, "Maaf, telah terjadi error");
                        }
                    } else {
                        stolenPlateTable.clearSelection();
                        this.selectedStolenPlateId = Optional.ofNullable(null);
                        this.selectedStolenPlateString = Optional.ofNullable(null);
                    }
                } else {
                    stolenPlateTable.clearSelection();
                    this.selectedStolenPlateId = Optional.ofNullable(null);
                    this.selectedStolenPlateString = Optional.ofNullable(null);
                    toBeAddedPlateNumberTextField.setText("");
                }
            } else {
                try {
                    String sql = "SELECT id FROM stolen_plates WHERE plate_number = ?";
                    PreparedStatement statement = this.mysqlConnection.prepareStatement(sql);
                    statement.setString(1, plateNumberToBeAdded);
                    ResultSet checkResult = statement.executeQuery();

                    if (checkResult.next()) {
                        JOptionPane.showMessageDialog(this, "Plat nomor ini telah ditambahkan sebelumnya");
                    } else {
                        String createSql = "INSERT INTO stolen_plates (plate_number, created_at) VALUES (?, ?)";
                        PreparedStatement createStatement = this.mysqlConnection.prepareStatement(createSql);
                        createStatement.setString(1, plateNumberToBeAdded);
                        createStatement.setDate(2, new Date(System.currentTimeMillis()));
                        createStatement.execute();

                        this.loadDataToStolenPlateTable();

                        toBeAddedPlateNumberTextField.setText("");

                        JOptionPane.showMessageDialog(this, "Plat nomor telah tersimpan");
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Maaf, telah terjadi error");
                }
            }
        }
    }//GEN-LAST:event_addStolenPlateButtonActionPerformed

    private void searchStolenPlateListActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_searchStolenPlateListActionPerformed
        this.loadDataToStolenPlateTable();
    }//GEN-LAST:event_searchStolenPlateListActionPerformed

    private void stolenPlateTableMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_stolenPlateTableMouseClicked
        Integer rowNumber = stolenPlateTable.rowAtPoint(evt.getPoint());
        this.selectedStolenPlateId = Optional.of(
            Integer
                .parseInt(
                    stolenPlateTable
                        .getValueAt(rowNumber, 0)
                        .toString()
                )
        );

        String selectedStolenPlateString = stolenPlateTable
                .getValueAt(rowNumber, 1)
                .toString();

        this.selectedStolenPlateString = Optional.of(selectedStolenPlateString);

        toBeAddedPlateNumberTextField.setText(selectedStolenPlateString);

    }//GEN-LAST:event_stolenPlateTableMouseClicked

    private void deleteStolenPlateButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_deleteStolenPlateButtonActionPerformed
        if (this.selectedStolenPlateId.isPresent()) {
            String question = "Apakah anda akan menghapus plat nomor " + this.selectedStolenPlateString.get() + " ?";
            Integer answer = JOptionPane.showConfirmDialog(this, question);
            if (answer == 0) {

                try {
                    PreparedStatement statement = this.mysqlConnection.prepareStatement("DELETE FROM stolen_plates WHERE id = ?");
                    statement.setInt(1, this.selectedStolenPlateId.get());
                    statement.execute();

                    this.loadDataToStolenPlateTable();
                    JOptionPane.showMessageDialog(this, "Plat nomor telah berhasil dihapus");

                    this.selectedStolenPlateId = Optional.ofNullable(null);
                    this.selectedStolenPlateString = Optional.ofNullable(null);

                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Maaf, telah terjadi error");
                }

            }
        } else {
            JOptionPane.showMessageDialog(this, "Pilih plat nomor yang akan dihapus");
        }
    }//GEN-LAST:event_deleteStolenPlateButtonActionPerformed

    private void detectedStolenPlateSearchButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_detectedStolenPlateSearchButtonActionPerformed
        this.loadDataToDetectedStolenPlateTable();
    }//GEN-LAST:event_detectedStolenPlateSearchButtonActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
            System.out.println(info.getName());
//                if ("Metal".equals(info.getName())) {
//                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
//                    break;
//                }
        }
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                System.out.println(info.getName());
                if ("GTK+".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(Application.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(Application.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(Application.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(Application.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                Application application = new Application(new Config());
                application.setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton addStolenPlateButton;
    private javax.swing.JButton deleteStolenPlateButton;
    private javax.swing.JTable detectedPlateNumberTable;
    private com.toedter.calendar.JDateChooser detectedStolenPlateAfterDateInput;
    private com.toedter.calendar.JDateChooser detectedStolenPlateBeforeDateInput;
    private javax.swing.JButton detectedStolenPlateSearchButton;
    private javax.swing.JTable detectedStolenPlateTable;
    private javax.swing.JPanel historyPanel;
    private javax.swing.JLabel imageContainerLabel;
    private javax.swing.JButton jButton3;
    private javax.swing.JButton jButton6;
    private javax.swing.JButton jButton7;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel8;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel10;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JPanel jPanel8;
    private javax.swing.JPanel jPanel9;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JScrollPane jScrollPane3;
    private javax.swing.JScrollPane jScrollPane4;
    private javax.swing.JPanel loadingPanel;
    private javax.swing.JPanel mainPanel;
    private javax.swing.JButton searchStolenPlateList;
    private javax.swing.JTextField searchStolenPlateTextField;
    private javax.swing.JPanel stolenPlateListPanel;
    private javax.swing.JTable stolenPlateTable;
    private javax.swing.JTextField toBeAddedPlateNumberTextField;
    private javax.swing.JPanel viewPanel;
    // End of variables declaration//GEN-END:variables
}
