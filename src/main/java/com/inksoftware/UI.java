package com.inksoftware;

import java.awt.Color;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.inksoftware.agent.AgentRouter;

public class UI extends JFrame {

    private final AgentRouter router;
    private final JTextField inputField;
    private final JTextArea chatHistory;

    private UI(AgentRouter router) {
        this.router = router;
        this.chatHistory = new JTextArea();
        this.chatHistory.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(chatHistory);

        this.inputField = new JTextField();
        this.inputField.addActionListener(e -> sendUserMessage());

        JButton sendButton = new JButton("Send");
        sendButton.setBackground(new Color(51, 204, 255));
        sendButton.setForeground(Color.WHITE);
        sendButton.addActionListener(e -> sendUserMessage());

        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new java.awt.BorderLayout());
        inputPanel.add(inputField, java.awt.BorderLayout.CENTER);
        inputPanel.add(sendButton, java.awt.BorderLayout.EAST);

        getContentPane().add(scrollPane, java.awt.BorderLayout.CENTER);
        getContentPane().add(inputPanel, java.awt.BorderLayout.SOUTH);
    }

    //Launch the UI with the given agent router
    public static void launch(AgentRouter router) {
        UI frame = new UI(router);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                int response = JOptionPane.showConfirmDialog(
                    frame,
                    "Are you sure you want to exit?",
                    "Confirm Exit",
                    JOptionPane.YES_NO_OPTION
                );

                if (response == JOptionPane.YES_OPTION) {
                    frame.chatHistory.append("Helper: Goodbye.\n");
                    frame.chatHistory.setCaretPosition(frame.chatHistory.getDocument().getLength());
                    frame.dispose();
                    System.exit(0);
                }
            }
        });
        frame.setSize(640, 480);
        frame.setTitle("AI Helper");
        frame.setVisible(true);
    }

    //Send user message, get response and add the message to chat history
    private void sendUserMessage() {
        String userInput = inputField.getText().trim();
        if (userInput.isEmpty()) {
            return;
        }

        appendMessage("You", userInput);
        inputField.setText("");

        String response = router.route(userInput);
        appendMessage("Helper", response);
    }

    private void appendMessage(String speaker, String message) {
        if (chatHistory.getDocument().getLength() > 0) {
            chatHistory.append("\n");
        }

        chatHistory.append(speaker + ":\n");
        chatHistory.append("  " + message.replace("\n", "\n  "));
        chatHistory.append("\n");
        chatHistory.setCaretPosition(chatHistory.getDocument().getLength());
    }
}