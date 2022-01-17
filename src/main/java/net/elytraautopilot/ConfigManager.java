package net.elytraautopilot;

import com.google.gson.Gson;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.TranslatableText;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

public class ConfigManager {
    public static ElytraConfig config;
    static File configFile;
    static MinecraftClient minecraftClient;

    public static void register(ElytraAutoPilot main, MinecraftClient mc) {
        minecraftClient = mc;
        Path configdir = FabricLoader.getInstance().getConfigDir();
        String moddir = "/elytraautopilot/config.json";
        configFile = new File(configdir + moddir);
        loadSettings(main);
    }

    public static void createAndShowSettings() {
        // This adds all config entries to the config screen
        // For some reason it doesn't remember the default settings afterwards
        // Might fix it later idk
        ConfigBuilder configBuilder = ConfigBuilder.create()
                .setTitle(new TranslatableText("text.elytraautopilot.title"))
                .setSavingRunnable(ConfigManager::saveSettings);

        ConfigEntryBuilder entryBuilder = ConfigEntryBuilder.create();

        ConfigCategory categoryGui = configBuilder
                .getOrCreateCategory(new TranslatableText("text.elytraautopilot.gui"));

        categoryGui.addEntry(entryBuilder
                .startBooleanToggle(new TranslatableText("text.elytraautopilot.showgui"), config.showgui)
                .setDefaultValue(config.showgui)
                .setSaveConsumer((x) -> config.showgui = x)
                .build());

        categoryGui.addEntry(entryBuilder
                .startIntField(new TranslatableText("text.elytraautopilot.guiX"), config.guiX)
                .setDefaultValue(config.guiX).setSaveConsumer((x) -> config.guiX = x)
                .build());

        categoryGui.addEntry(entryBuilder
                .startIntField(new TranslatableText("text.elytraautopilot.guiY"), config.guiY)
                .setDefaultValue(config.guiY).setSaveConsumer((y) -> config.guiY = y)
                .build());

        ConfigCategory categoryFlightProfile = configBuilder
                .getOrCreateCategory(new TranslatableText(("text.elytraautopilot.flightprofile")));

        categoryFlightProfile.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.maxHeight"), config.maxHeight)
                .setDefaultValue(config.maxHeight)
                .setSaveConsumer((y) -> config.maxHeight = y)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.minHeight"), config.minHeight)
                .setDefaultValue(config.minHeight)
                .setSaveConsumer((y) -> config.minHeight = y)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.landingSpeed"), config.landingSpeed)
                .setDefaultValue(config.landingSpeed).setSaveConsumer((x) -> config.landingSpeed = x)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.turningSpeed"), config.turningSpeed)
                .setDefaultValue(config.turningSpeed).setSaveConsumer((x) -> config.turningSpeed = x)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.takeOffPull"), config.takeOffPull)
                .setDefaultValue(config.takeOffPull).setSaveConsumer((x) -> config.takeOffPull = x)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startBooleanToggle(new TranslatableText("text.elytraautopilot.autoLanding"), config.autoLanding)
                .setDefaultValue(config.autoLanding).setSaveConsumer((x) -> config.autoLanding = x)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startBooleanToggle(new TranslatableText("text.elytraautopilot.poweredFlight"), config.poweredFlight)
                .setDefaultValue(config.poweredFlight)
                .setSaveConsumer((x) -> config.poweredFlight = x)
                .build());

        categoryFlightProfile.addEntry(entryBuilder
                .startBooleanToggle(new TranslatableText("text.elytraautopilot.elytraHotswap"), config.elytraHotswap)
                .setDefaultValue(config.elytraHotswap)
                .setSaveConsumer((x) -> config.elytraHotswap = x)
                .build());

        ConfigCategory categoryAdvanced = configBuilder
                .getOrCreateCategory(new TranslatableText(("text.elytraautopilot.advanced")));

        categoryAdvanced.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.pullUpAngle"), config.pullUpAngle)
                .setDefaultValue(config.pullUpAngle)
                .setSaveConsumer((y) -> config.pullUpAngle = y)
                .build());

        categoryAdvanced.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.pullDownAngle"), config.pullDownAngle)
                .setDefaultValue(config.pullDownAngle)
                .setSaveConsumer((y) -> config.pullDownAngle = y)
                .build());

        categoryAdvanced.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.pullUpSpeed"), config.pullUpSpeed)
                .setDefaultValue(config.pullUpSpeed)
                .setSaveConsumer((y) -> config.pullUpSpeed = y)
                .build());

        categoryAdvanced.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.pullDownSpeed"), config.pullDownSpeed)
                .setDefaultValue(config.pullDownSpeed)
                .setSaveConsumer((y) -> config.pullDownSpeed = y)
                .build());

        categoryAdvanced.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.pullUpMinVelocity"),
                        config.pullUpMinVelocity)
                .setDefaultValue(config.pullUpMinVelocity)
                .setSaveConsumer((y) -> config.pullUpMinVelocity = y)
                .build());

        categoryAdvanced.addEntry(entryBuilder
                .startDoubleField(new TranslatableText("text.elytraautopilot.pullDownMaxVelocity"),
                        config.pullDownMaxVelocity)
                .setDefaultValue(config.pullDownMaxVelocity)
                .setSaveConsumer((y) -> config.pullDownMaxVelocity = y)
                .build());

        Screen screen = configBuilder.build();
        MinecraftClient.getInstance().setScreen(screen);
    }

    private static void saveSettings() {
        // Writes config values to Json
        Gson gson = new Gson();
        String configString = gson.toJson(config);
        try {
            FileWriter writer = new FileWriter(configFile);
            writer.write(configString);
            writer.close();
            System.out.println("Saved ElytraAutoPilot settings");
        } catch (IOException e) {
            System.out.println("Error ElytraAutoPilot saving settings!");
        }
    }

    public static void loadSettings(ElytraAutoPilot main) {
        config = new ElytraConfig();
        // Check if the file exists, otherwise create it
        if (!configFile.exists() && configFile.getParentFile().mkdirs()) {
            try {
                if (configFile.createNewFile()) {
                    System.out.println("Created new ElytraAutoPilot config file");
                }
            } catch (IOException e) {
                System.out.println("Unable to load ElytraAutoPilot settings! Using default config");
            }
        } else {
            try {
                // Kind of a weird solution but oh well
                Scanner scanner = new Scanner(configFile);
                String output = "";
                while (scanner.hasNextLine()) {
                    scanner = new Scanner(configFile);
                    output = scanner.nextLine();
                }
                scanner.close();

                // Load settings from Json
                if (!output.equals("")) {
                    Gson gson = new Gson();
                    config = gson.fromJson(output, ElytraConfig.class);
                    System.out.println("Loaded ElytraAutoPilot Settings");
                } else
                    System.out.println("Unable to load ElytraAutoPilot settings! Using default config");
            } catch (IOException e) {
                System.out.println("Unable to load ElytraAutoPilot settings! Using default config");
            }
        }
        main.config = config;
    }
}
