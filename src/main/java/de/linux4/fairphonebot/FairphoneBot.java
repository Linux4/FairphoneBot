/*
   Copyright (C) 2025  Tim Zimmermann <tim@linux4.de>

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU Affero General Public License as
   published by the Free Software Foundation, either version 3 of the
   License, or (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU Affero General Public License for more details.

   You should have received a copy of the GNU Affero General Public License
   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.linux4.fairphonebot;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.projects.BranchInfo;
import com.google.gerrit.extensions.api.projects.TagInfo;
import com.google.gerrit.extensions.common.ProjectInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.gerrit.client.rest.GerritRestApiFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

public class FairphoneBot implements LongPollingSingleThreadUpdateConsumer {

    public record TelegramMessage(String channelId, String text) {
    }

    public static final String FIRMWARE_URL
            = "https://support.fairphone.com/hc/en-us/articles/18896094650513-How-to-manually-install-Android-on-your-Fairphone";

    public static final String GERRIT_BASE = "https://gerrit-public.fairphone.software";
    public static final String GITILES_BASE = GERRIT_BASE + "/plugins/gitiles/";

    public static void main(String[] args) {
        if (args.length != 2 && args.length != 3) {
            System.out.println("Usage: java -jar fairphonebot.jar <bot token> <channel> [oneshot]");
            System.exit(1);
        }

        boolean oneshot = args.length == 3 && args[2].equalsIgnoreCase("oneshot");

        try {
            TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
            FairphoneBot bot = new FairphoneBot(args[0], args[1], oneshot);
            botsApplication.registerBot(args[0], bot);
            bot.run();
            botsApplication.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(3 * 1000); // 3s - prevent telegram spam protection
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void compare(Collection<String> oldValues, Collection<String> newValues,
                                Consumer<String> removedCallback, Consumer<String> addedCallback) {
        for (String oldValue : oldValues) {
            if (!newValues.contains(oldValue)) {
                removedCallback.accept(oldValue);
            }
        }
        for (String newValue : newValues) {
            if (!oldValues.contains(newValue)) {
                addedCallback.accept(newValue);
            }
        }
    }

    private final String channel;
    private final boolean oneshot;
    private final TelegramClient telegramClient;
    private boolean checksFinished = false;
    private final ConcurrentLinkedQueue<TelegramMessage> messageQueue = new ConcurrentLinkedQueue<>();

    public FairphoneBot(String botToken, String channel, boolean oneshot) {
        this.channel = channel;
        this.oneshot = oneshot;

        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    public void run() {
        FairphoneFirmwareDatabase firmwareDb = new FairphoneFirmwareDatabase("db/fairphonefirmware.db");
        FairphoneGerritDatabase gerritDb = new FairphoneGerritDatabase("db/fairphonegerrit.db");

        ExecutorService messageExecutor = Executors.newSingleThreadExecutor();
        ThreadPoolExecutor gerritCheckExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);

        messageExecutor.submit(() -> {
            System.out.println("Message thread start");
            while (!messageQueue.isEmpty() || !checksFinished) {
                System.out.println("Message Queue Size: " + messageQueue.size() + " checksFinished=" + checksFinished);
                if (!messageQueue.isEmpty()) {
                    TelegramMessage message = messageQueue.poll();

                    // Telegram message length limit: 4096
                    SendMessage sm = new SendMessage(message.channelId(),
                            message.text().substring(0, Math.min(message.text().length(), 4096)));
                    sm.setChatId(message.channelId());
                    sm.setParseMode("Markdown");

                    for (int i = 0; i < 5; i++) {
                        try {
                            telegramClient.execute(sm);
                            break;
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                            sleep();
                        }
                    }
                }
                sleep();
            }
            System.out.println("Message thread end");

            if (oneshot)
                System.exit(0);
        });

        do {
            // 1. Check gerrit
            GerritRestApiFactory gerritApiFactory = new GerritRestApiFactory();
            GerritAuthData.Basic authData = new GerritAuthData.Basic("https://gerrit-public.fairphone.software");
            GerritApi gerritApi = gerritApiFactory.create(authData);

            try {
                List<String> projects = gerritDb.getProjects();
                List<String> newProjects = new ArrayList<>();

                for (ProjectInfo info : gerritApi.projects().list().get()) {
                    newProjects.add(info.name);
                }

                compare(projects, newProjects, (existingProject) -> {
                    messageQueue.add(new TelegramMessage(channel, "Project deleted! `" + existingProject + "`"));
                    gerritDb.removeProject(existingProject);
                },(newProject) -> {
                    messageQueue.add(new TelegramMessage(channel, "New project detected! `" + newProject + "`\n"
                            + "[Check Here](" + GITILES_BASE + newProject + ")"));
                    gerritDb.addProject(newProject);
                });

                for (String project : newProjects) {
                    // Compare branches
                    gerritCheckExecutor.submit(() -> {
                        try {
                            Map<String, String> branches = gerritDb.getBranches(project);
                            Map<String, String> newBranches = new HashMap<>();

                            for (BranchInfo info : gerritApi.projects().name(project).branches().get()) {
                                newBranches.put(info.ref, info.revision);
                            }

                            compare(branches.keySet(), newBranches.keySet(), (existingBranch) -> {
                                messageQueue.add(new TelegramMessage(channel, "Branch deleted! `" + project + "`"
                                        + " @ `" + existingBranch + "`"));
                                gerritDb.removeBranch(project, existingBranch);
                            }, (newBranch) -> {
                                messageQueue.add(new TelegramMessage(channel, "New branch detected! `" + project + "`"
                                        + " @ `" + newBranch + "`\n"
                                        + "[Check Here](" + GITILES_BASE + project + "/+/" + newBranch + ")"));
                                gerritDb.addBranch(project, newBranch);
                                gerritDb.setBranchRevision(project, newBranch, newBranches.get(newBranch));
                            });

                            for (String existingBranch : branches.keySet()) {
                                if (newBranches.containsKey(existingBranch)) {
                                    String existingRevision = branches.get(existingBranch);
                                    String newRevision = newBranches.get(existingBranch);

                                    if (!existingRevision.equals(newRevision)) {
                                        messageQueue.add(new TelegramMessage(channel, "New commits detected!"
                                                + "`" + project + "` @ `" + existingBranch + "`\n"
                                                + "`" + newRevision + "`\n"
                                                + "[Check Here](" + GITILES_BASE + project + "/+log/" + newRevision + ")"));
                                    }
                                }
                            }
                        } catch (RestApiException ex) {
                            ex.printStackTrace();
                        }
                    });

                    // Compare tags
                    gerritCheckExecutor.submit(() -> {
                        try {
                            List<String> tags = gerritDb.getTags(project);
                            List<String> newTags = new ArrayList<>();

                            for (TagInfo info : gerritApi.projects().name(project).tags().get()) {
                                newTags.add(info.ref);
                            }

                            compare(tags, newTags, (existingTag) -> {
                                messageQueue.add(new TelegramMessage(channel, "Tag deleted! `" + project + "`"
                                        + " @ `" + existingTag + "`"));
                                gerritDb.removeTag(project, existingTag);
                            }, (newTag) -> {
                                messageQueue.add(new TelegramMessage(channel, "New tag detected! `" + project + "`"
                                        + " @ `" + newTag + "`\n"
                                        + "[Check Here](" + GITILES_BASE + project + "/+/" + newTag + ")"));
                                gerritDb.addTag(project, newTag);
                            });
                        } catch (RestApiException ex) {
                            ex.printStackTrace();
                        }
                    });
                }
            } catch (RestApiException ex) {
                ex.printStackTrace();
            }

            // 2. Check firmware
            try {
                Playwright playwright = Playwright.create();
                Browser browser = playwright.chromium().launch();
                BrowserContext context = browser.newContext(
                        new Browser.NewContextOptions()
                                .setViewportSize(1280, 800)
                                .setUserAgent(
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                                "Chrome/129.0.0.0 Safari/537.36"
                                )
                );
                Page page = context.newPage();
                page.navigate(FIRMWARE_URL);
                Document doc = Jsoup.parse(page.content());

                for (Element device : doc.select("h4:has(+ .accordion)")) {
                    Element buildList = device.nextElementSibling().nextElementSibling();
                    Element downloadLink = buildList.firstElementChild();
                    // FP3
                    if (downloadLink.hasClass("accordion"))
                        downloadLink = downloadLink.nextElementSibling().firstElementChild();
                    Element buildInfo = downloadLink.nextElementSibling();

                    String model = device.text();
                    String download = downloadLink.firstChild().attr("href");
                    String[] downloadParts = download.split("/");
                    String codename = downloadParts[3];
                    String androidVersion = downloadParts[4].substring(1);
                    String fileName = downloadParts[5];
                    Map<String, String> buildProperties = new HashMap<>();

                    for (Element property : buildInfo.select("span:has(> strong)")) {
                        String content = property.html().replaceAll("\n", "");
                        String[] contentParts = content.split("<strong>");

                        for (String part : contentParts) {
                            part = part.replaceAll("</strong>", "").replaceAll("<br>", "");

                            if (!part.trim().isEmpty()) {
                                buildProperties.put(part.split(":")[0].trim(), part.split(":")[1].trim());
                            }
                        }
                    }

                    if (!firmwareDb.getFirmwareVersion(model).equals(buildProperties.get("Version"))) {
                        StringBuilder messageBuilder = new StringBuilder("New build detected!\n");
                        messageBuilder.append("Device: `").append(model).append("`").append("\n");
                        messageBuilder.append("Codename: `").append(codename).append("`").append("\n");
                        messageBuilder.append("Android Version: `").append(androidVersion).append("`").append("\n");
                        for (String property : buildProperties.keySet()) {
                            messageBuilder.append(property).append(": `").append(buildProperties.get(property)).append("`")
                                    .append("\n");
                        }
                        messageBuilder.append("Filename: `").append(fileName).append("`").append("\n");
                        messageBuilder.append("[Download](").append(download).append(")").append("\n");

                        messageQueue.add(new TelegramMessage(channel, messageBuilder.toString()));
                        firmwareDb.setFirmwareVersion(model, buildProperties.get("Version"));
                    }
                }
            } catch (Exception | Error ex) {
                ex.printStackTrace();
            }

            if (!oneshot) {
                try {
                    Thread.sleep(60 * 60 * 1000); // 1h
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            int activeThreadsCount;
            do {
                activeThreadsCount = gerritCheckExecutor.getActiveCount();
                System.out.println("Still active Threads: " + activeThreadsCount);
                sleep();
            } while (activeThreadsCount > 0);
        } while (!oneshot);

        checksFinished = true;
        System.out.println("Checks finished");

        messageExecutor.close();
        gerritCheckExecutor.close();
    }

    @Override
    public void consume(Update update) {

    }
}
