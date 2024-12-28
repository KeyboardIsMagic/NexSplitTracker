package com.Utils;

import com.osrs_splits.OsrsSplitPlugin;
import com.osrs_splits.PartyManager.PlayerInfo;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import org.json.JSONArray;
import java.util.Set;
import org.json.JSONObject;

import java.util.*;


import javax.swing.*;
import java.net.URISyntaxException;
import java.util.Timer;

public class PartySocketIOClient
{
    private final OsrsSplitPlugin plugin;
    private Socket socket;

    public PartySocketIOClient(String serverUrl, OsrsSplitPlugin plugin)
    {
        this.plugin = plugin;

        try
        {
            socket = IO.socket(serverUrl);

            // Connected
            socket.on(Socket.EVENT_CONNECT, args -> {
                System.out.println("Socket.IO Connected to the server.");
            });

            // Event: Party Update
            socket.on("party_update", args -> {
                try
                {
                    JSONObject partyData = new JSONObject(args[0].toString());
                    System.out.println("Party Update Received: " + partyData);

                    // Process the received update
                    processPartyUpdate(partyData);

                    // Ack
                    socket.emit("ack_party_update", "Party update processed successfully");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    System.err.println("Failed to process party update: " + e.getMessage());
                }
            });

            // Event: joinPartyError
            socket.on("joinPartyError", args -> {
                SwingUtilities.invokeLater(() -> {
                    try
                    {
                        JSONObject obj = new JSONObject(args[0].toString());
                        String msg = obj.optString("message", "Party join error");
                        System.out.println("Received 'joinPartyError': " + obj);

                        // Show an error only to the user who tried to join
                        JOptionPane.showMessageDialog(
                                null,
                                msg,
                                "Join Party Failed",
                                JOptionPane.ERROR_MESSAGE
                        );

                        // Clear passphrase so we don't show a blank panel
                        plugin.getPartyManager().setCurrentPartyPassphrase(null);

                        // Re-enable create/join
                        plugin.getPanel().getCreatePartyButton().setEnabled(true);
                        plugin.getPanel().getJoinPartyButton().setEnabled(true);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                });
            });

            // "response" listener
            socket.on("response", args -> {
                SwingUtilities.invokeLater(() -> {
                    try
                    {
                        JSONObject obj = new JSONObject(args[0].toString());
                        String status = obj.optString("status", "");
                        String message = obj.optString("message", "");
                        System.out.println("Received 'response': " + obj);

                        if ("success".equalsIgnoreCase(status))
                        {
                            // Show no pop-ups for success (party created, user joined, etc.)
                            // Just update the UI to hide create/join and show leave
                            plugin.getPanel().enableLeaveParty();
                            plugin.getPanel().updatePartyMembers();
                        }
                        else
                        {
                            // If it's not "success", we do show an error
                            JOptionPane.showMessageDialog(
                                    null,
                                    "Unexpected server response: " + message,
                                    "Join/Create Party Error",
                                    JOptionPane.ERROR_MESSAGE
                            );
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                });
            });

            // Connect/Timeout/Disconnect logs
            socket.on("connect_error", args -> {
                System.err.println("Socket.IO Connection Error: " + args[0]);
            });
            socket.on("connect_timeout", args -> {
                System.err.println("Socket.IO Connection Timeout.");
            });
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                System.out.println("Socket.IO Disconnected.");
            });

            socket.connect();
        }
        catch (URISyntaxException e)
        {
            System.err.println("Invalid server URL: " + e.getMessage());
            e.printStackTrace();
        }
    }




    public PartySocketIOClient(OsrsSplitPlugin plugin)
    {
        this.plugin = plugin;
    }


    public void sendCreateParty(String passphrase, String rsn, int world, String apiKey)
    {
        if (rsn == null || rsn.isEmpty())
        {
            System.err.println("Invalid RSN: " + rsn);
            return;
        }

        JSONObject payload = new JSONObject();
        payload.put("passphrase", passphrase);
        payload.put("rsn", rsn);
        payload.put("world", world);
        payload.put("apiKey", apiKey); // Make sure we do pass it

        // Extra debug
        System.out.println("Creating party with payload: " + payload);

        socket.emit("create-party", payload);
        System.out.println("Sent create-party event with payload: " + payload);
    }






    public void sendJoinParty(String passphrase, String rsn, int world, String apiKey) {
        JSONObject payload = new JSONObject();
        payload.put("passphrase", passphrase);
        payload.put("rsn", rsn);
        payload.put("world", world);
        payload.put("apiKey", apiKey); // Include the API key for server verification

        socket.emit("join-party", payload);
        System.out.println("Sent join-party event with payload: " + payload);
    }


    public void disconnect() {
        if (socket != null && socket.connected()) {
            socket.disconnect();
            System.out.println("Socket.IO client disconnected.");
        } else {
            System.out.println("Socket.IO client is already disconnected.");
        }
    }

    private void processPartyUpdate(Object data)
    {
        SwingUtilities.invokeLater(() ->
        {
            try
            {
                JSONObject json = new JSONObject(data.toString());
                String action = json.optString("action", "");
                String passphrase = json.optString("passphrase", "");
                String leader = json.optString("leader", null);
                JSONArray membersArray = json.optJSONArray("members");

                if (!passphrase.equals(plugin.getPartyManager().getCurrentPartyPassphrase()))
                {
                    System.out.println("Ignoring update for mismatched passphrase: " + passphrase);
                    return;
                }

                // If server says "party_disband"
                if ("party_disband".equals(action))
                {
                    System.out.println("Received party_disband for " + passphrase);

                    plugin.getPartyManager().clearMembers();
                    plugin.getPartyManager().setCurrentPartyPassphrase(null);
                    plugin.getPartyManager().setLeader(null);

                    // Re-enable create/join for local
                    plugin.getPanel().getCreatePartyButton().setEnabled(true);
                    plugin.getPanel().getJoinPartyButton().setEnabled(true);

                    plugin.getPanel().updatePartyMembers();
                    plugin.getPanel().updatePassphraseLabel("");
                    plugin.getPanel().getPassphraseLabel().setVisible(false);

                    return;
                }

                // Build updated list
                Map<String, PlayerInfo> updatedMembers = new HashMap<>();
                if (membersArray != null)
                {
                    for (int i = 0; i < membersArray.length(); i++)
                    {
                        JSONObject memberJson = membersArray.getJSONObject(i);
                        PlayerInfo playerInfo = new PlayerInfo(
                                memberJson.getString("name"),
                                memberJson.optInt("world", -1),
                                memberJson.optInt("rank", -1),
                                memberJson.optBoolean("verified", false),
                                memberJson.optBoolean("confirmedSplit", false)
                        );
                        updatedMembers.put(playerInfo.getName(), playerInfo);
                    }
                }

                // If no members remain -> clear local data, re-enable create/join
                if (updatedMembers.isEmpty())
                {
                    System.out.println("No members in party " + passphrase + ". Clearing local data.");
                    plugin.getPartyManager().clearMembers();
                    plugin.getPartyManager().setCurrentPartyPassphrase(null);
                    plugin.getPartyManager().setLeader(null);

                    plugin.getPanel().getCreatePartyButton().setEnabled(true);
                    plugin.getPanel().getJoinPartyButton().setEnabled(true);

                    plugin.getPanel().updatePartyMembers();
                    plugin.getPanel().updatePassphraseLabel("");
                    plugin.getPanel().getPassphraseLabel().setVisible(false);

                    return;
                }

                // If local player is not in updatedMembers, we parted ways
                String localPlayerName = plugin.getClient().getLocalPlayer() != null
                        ? plugin.getClient().getLocalPlayer().getName()
                        : null;

                if (localPlayerName != null && !updatedMembers.containsKey(localPlayerName))
                {
                    System.out.println("Local player " + localPlayerName + " is not in updated list. Clearing local data...");
                    plugin.getPartyManager().clearMembers();
                    plugin.getPartyManager().setCurrentPartyPassphrase(null);
                    plugin.getPartyManager().setLeader(null);

                    plugin.getPanel().getCreatePartyButton().setEnabled(true);
                    plugin.getPanel().getJoinPartyButton().setEnabled(true);

                    plugin.getPanel().updatePartyMembers();
                    plugin.getPanel().updatePassphraseLabel("");
                    plugin.getPanel().getPassphraseLabel().setVisible(false);

                    return;
                }

                // Otherwise, local player is included, so update local data
                plugin.getPartyManager().updateCurrentParty(passphrase, updatedMembers);
                plugin.getPartyManager().setLeader(leader);

                // Keep passphrase label visible
                plugin.getPanel().updatePassphraseLabel(passphrase);
                plugin.getPanel().getPassphraseLabel().setVisible(true);

                // Refresh the UI
                plugin.getPanel().updatePartyMembers();
            }
            catch (Exception e)
            {
                e.printStackTrace();
                System.err.println("Failed to process party update: " + e.getMessage());
            }
        });
    }








    public void fetchBatchVerification(Set<String> rsns, String apiKey)
    {
        if (apiKey == null || apiKey.isEmpty()) {
            System.out.println("API key is missing. Cannot verify RSNs.");
            return;
        }

        JSONObject payload = new JSONObject();
        payload.put("apiKey", apiKey);
        payload.put("rsns", new JSONArray(rsns));

        try {
            String response = HttpUtil.postRequest("http://127.0.0.1:5000/verify-batch", payload.toString());
            JSONObject jsonResponse = new JSONObject(response);

            if (jsonResponse.optBoolean("verified", false)) {
                JSONArray rsnData = jsonResponse.optJSONArray("rsnData");
                if (rsnData != null) {
                    for (int i = 0; i < rsnData.length(); i++) {
                        JSONObject rsnObject = rsnData.optJSONObject(i);
                        if (rsnObject != null && rsnObject.has("name")) {
                            String name = rsnObject.optString("name", null);
                            int rank = rsnObject.optInt("rank", -1);
                            boolean verified = rsnObject.optBoolean("verified", false);

                            if (name != null) {
                                // 1) See if we already have a record for that user:
                                PlayerInfo existing = plugin.getPartyManager().getMembers().get(name);

                                if (existing != null) {
                                    // Keep existing world, just update rank & verified
                                    existing.setRank(rank);
                                    existing.setVerified(verified);
                                }
                                else {
                                    // 2) If it's the local user, we can store the real local world
                                    int realWorld = -1;
                                    if (plugin.getClient().getLocalPlayer() != null
                                            && name.equalsIgnoreCase(plugin.getClient().getLocalPlayer().getName()))
                                    {
                                        realWorld = plugin.getClient().getWorld();
                                    }

                                    // Create new record, using either the realWorld or -1 as fallback
                                    PlayerInfo newInfo = new PlayerInfo(name, realWorld, rank, verified, false);
                                    plugin.getPartyManager().addMember(newInfo);
                                }
                            } else {
                                System.err.println("Invalid RSN data: missing or null name.");
                            }
                        } else {
                            System.err.println("Invalid RSN object: " + rsnObject);
                        }
                    }

                    // Update UI
                    plugin.getPanel().updatePartyMembers();
                } else {
                    System.err.println("No rsnData array in batch verification response.");
                }
            } else {
                System.err.println("Batch verification failed or unverified.");
            }

            // Immediately update UI
            plugin.getPanel().updatePartyMembers();
        }
        catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to fetch batch verification: " + e.getMessage());
        }
    }








    public void emitClientState() {
        Timer timer = new Timer(); // Create a Timer instance
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (plugin.getPartyManager().isInParty(plugin.getClient().getLocalPlayer().getName())) {
                    JSONObject payload = new JSONObject();
                    payload.put("passphrase", plugin.getPartyManager().getCurrentPartyPassphrase());
                    payload.put("name", plugin.getClient().getLocalPlayer().getName());
                    payload.put("world", plugin.getClient().getWorld());
                    plugin.getWebSocketClient().send("client_state_update", payload.toString());
                }
            }
        }, 0, 5000); // Delay of 0 ms, repeat every 5000 ms (5 seconds)
    }





    public void sendLeaveParty(String passphrase, String rsn) {
        JSONObject payload = new JSONObject();
        payload.put("passphrase", passphrase);
        payload.put("rsn", rsn);

        socket.emit("leave-party", payload);
        System.out.println("Sent leave-party event: " + payload);
    }

    public void sendUpdateParty(String payload) {
        socket.emit("party_update", payload);
        System.out.println("Sent party_update event: " + payload);
    }

    public boolean isOpen() {
        return socket.connected();
    }

    public void reconnect() {
        if (!socket.connected()) {
            socket.connect();
            System.out.println("Reconnecting to Socket.IO server...");
            if (plugin.getPartyManager().getCurrentPartyPassphrase() != null) {
                sendJoinParty(
                        plugin.getPartyManager().getCurrentPartyPassphrase(),
                        plugin.getClient().getLocalPlayer().getName(),
                        plugin.getClient().getWorld(), // Add the current world
                        plugin.getConfig().apiKey()    // Add the API key
                );
            }
        }
    }



    public void send(String event, String payload) {
        socket.emit(event, payload);
        System.out.println("Sent event [" + event + "] with payload: " + payload);
    }

    public void sendPartyUpdate(String passphrase, Map<String, PlayerInfo> members) {
        JSONObject payload = new JSONObject();
        payload.put("action", "party_update");
        payload.put("passphrase", passphrase);

        JSONArray memberArray = new JSONArray();
        for (PlayerInfo member : members.values()) {
            JSONObject memberData = new JSONObject();
            memberData.put("name", member.getName());
            memberData.put("world", member.getWorld());
            memberData.put("rank", member.getRank());
            memberData.put("verified", member.isVerified());
            memberData.put("confirmedSplit", member.isConfirmedSplit());
            memberArray.put(memberData);
        }

        payload.put("members", memberArray);

        socket.emit("party_update", payload.toString());
        System.out.println("Sent party_update event: " + payload);
    }



    public void sendDisbandParty(String passphrase) {
        JSONObject payload = new JSONObject();
        payload.put("action", "party_disband");
        payload.put("passphrase", passphrase);

        // Emit the event
        socket.emit("party_disband", payload.toString());
        System.out.println("Sent party_disband event: " + payload);
    }


}