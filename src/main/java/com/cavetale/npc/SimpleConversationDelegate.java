package com.cavetale.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

@RequiredArgsConstructor
public final class SimpleConversationDelegate implements Conversation.Delegate {
    private final ConfigurationSection config;

    @Override
    public int onTimeout(Conversation convo) {
        String state = convo.getState();
        if (state == null) return 0;
        if (config.isString(state)) {
            List<String> keys = new ArrayList<>(config.getKeys(false));
            int index = keys.indexOf(state);
            if (index < keys.size() - 1) {
                convo.setState(keys.get(index + 1));
            } else {
                convo.setState("exit");
            }
            return convo.say(0, Arrays.asList(config.getString(state).split("\n")));
        } else if (config.isList(state)) {
            List<String> keys = new ArrayList<>(config.getKeys(false));
            int index = keys.indexOf(state);
            if (index < keys.size() - 1) {
                convo.setState(keys.get(index + 1));
            } else {
                convo.setState("exit");
            }
            return convo.say(0, config.getStringList(state));
        }
        ConfigurationSection section = config.getConfigurationSection(state);
        if (section == null) return 0;
        int result = 0;
        if (section.isString("say")) {
            result = convo.say(Arrays.asList(section.getString("say").split("\n")));
        } else if (section.isList("say")) {
            result = convo.say(section.getStringList("say"));
        } else if (section.isConfigurationSection("say")) {
            ConfigurationSection saySection = section.getConfigurationSection("say");
            for (String sayKey: saySection.getKeys(false)) {
                NPC npc = null;
                int npcIndex = 0;
                for (NPC n: convo.getNpcs()) {
                    if (sayKey.equals(n.getUniqueName())) {
                        npc = n;
                        break;
                    }
                    npcIndex += 1;
                }
                if (npc == null) {
                    System.err.println("Convo: NPC not found: " + sayKey);
                    npcIndex = 0;
                }
                Object o = saySection.get(sayKey);
                if (o instanceof String) {
                    result = convo.say(npcIndex, Arrays.asList(((String)o).split("\n")));
                } else if (o instanceof List) {
                    result = convo.say(npcIndex, (List<String>)o);
                }
            }
        }
        if (section.isList("commands")) {
            for (String cmd: section.getStringList("commands")) {
                convo.getPlayers().get(0).performCommand(cmd);
            }
        } else if (section.isList("console")) {
            for (String cmd: section.getStringList("console")) {
                cmd = cmd.replace("%player%", convo.getPlayers().get(0).getName());
                cmd = cmd.replace("%uuid%", convo.getPlayers().get(0).getUniqueId().toString());
                Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), cmd);
            }
        }
        String question = section.getString("question");
        String questioner = section.getString("questioner");
        int questionerIndex = 0;
        if (questioner != null) {
            for (NPC n: convo.getNpcs()) {
                if (questioner.equals(n.getUniqueName())) {
                    break;
                }
                questionerIndex += 1;
            }
            if (questionerIndex > convo.getNpcs().size()) {
                System.err.println("Convo: NPC not found: " + questioner);
                questionerIndex = 0;
            }
        }
        if (section.isConfigurationSection("options")) {
            ConfigurationSection optionsSection = section.getConfigurationSection("options");
            List<Conversation.Option> options = new ArrayList<>();
            for (String optKey: optionsSection.getKeys(false)) {
                Conversation.Option option;
                if (optionsSection.isString(optKey)) {
                    option = new Conversation.Option(optionsSection.getString(optKey), optKey);
                } else if (optionsSection.isConfigurationSection(optKey)) {
                    ConfigurationSection optSection = optionsSection.getConfigurationSection(optKey);
                    String text = optSection.getString("text");
                    option = new Conversation.Option(text, optKey);
                    if (optSection.isString("color")) {
                        try {
                            option.setColor(ChatColor.valueOf(optSection.getString("color").toUpperCase()));
                        } catch (IllegalArgumentException iae) {
                            iae.printStackTrace();
                        }
                    }
                } else {
                    option = null;
                    System.err.println("Bad conversation option: " + optionsSection.get(optKey));
                }
                if (option != null) options.add(option);
            }
            result = convo.sayOptions(questionerIndex, question, options);
        }
        if (section.isString("next")) {
            convo.setState(section.getString("next"));
        } else if (section.isString("nextExit")) {
            convo.getNpcs().get(0).setConversationState(section.getString("nextExit"));
            convo.setState("exit");
        } else {
            convo.setState("exit");
        }
        return result;
    }
}
