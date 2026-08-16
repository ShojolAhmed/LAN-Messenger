package com.lanmessenger.client.ui;

import com.lanmessenger.client.ui.model.ChatMessage;
import com.lanmessenger.client.ui.model.ChatUser;
import com.lanmessenger.client.ui.model.Conversation;
import com.lanmessenger.client.ui.model.ConversationKind;
import com.lanmessenger.client.ui.model.Presence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Sample content used to bring the UI to life during this design phase.
 *
 * <p><b>Nothing here touches the networking layer.</b> The goal of this phase is
 * to establish the visual system, so the sidebar, header, message list and
 * composer are driven entirely by these in-memory fixtures. A later phase swaps
 * this out for real data from {@code client.net} without changing the components.
 */
public final class SampleData {

    private SampleData() {
        // Utility class.
    }

    /** @return the roster of sample users (with varied presence states). */
    public static List<ChatUser> users() {
        return List.of(
                new ChatUser("Rahim", Presence.ONLINE),
                new ChatUser("Karim", Presence.ONLINE),
                new ChatUser("Sakib", Presence.ONLINE),
                new ChatUser("Nadia", Presence.AWAY),
                new ChatUser("Tanvir", Presence.OFFLINE)
        );
    }

    /**
     * Builds the sample conversations shown in the sidebar. The first entry is the
     * global room (selected by default); the rest are direct chats, including one
     * with no messages yet so the empty state is demonstrable.
     *
     * @return an ordered list of conversations
     */
    public static List<Conversation> conversations() {
        return List.of(globalChat(), rahim(), karim(), sakib(), nadia());
    }

    private static Conversation globalChat() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        return Conversation.builder("global", ConversationKind.GLOBAL, "Global Chat")
                .headerSubtitle("Everyone on the LAN \u00b7 5 online")
                .sidebarSubtitle("Public channel \u00b7 everyone")
                .messages(List.of(
                        ChatMessage.system("This is the beginning of the Global channel.", at(yesterday, 9, 0)),
                        ChatMessage.incoming("Rahim", "Morning everyone! Server's up on the LAN.", at(yesterday, 9, 12)),
                        ChatMessage.incoming("Karim", "Nice, connecting now.", at(yesterday, 9, 13)),
                        ChatMessage.incoming("Sakib", "Works great over here.", at(yesterday, 9, 15)),
                        ChatMessage.outgoing("Awesome — glad it's stable.", at(yesterday, 9, 16)),
                        ChatMessage.system("Nadia joined the channel.", at(today, 8, 40)),
                        ChatMessage.incoming("Nadia", "Hi all, just joined the channel.", at(today, 8, 41)),
                        ChatMessage.incoming("Rahim", "Welcome, Nadia! The UI got a fresh new look today.", at(today, 8, 42)),
                        ChatMessage.outgoing("Yeah, check out the new message bubbles and sidebar.", at(today, 8, 44)),
                        ChatMessage.incoming("Karim", "Looks clean. Love the dark theme.", at(today, 8, 45))
                ))
                .build();
    }

    private static Conversation rahim() {
        LocalDate today = LocalDate.now();
        return Conversation.builder("dm-rahim", ConversationKind.DIRECT, "Rahim")
                .peer(new ChatUser("Rahim", Presence.ONLINE))
                .headerSubtitle(Presence.ONLINE.label())
                .sidebarSubtitle("Can you review the layout?")
                .unread(2)
                .messages(List.of(
                        ChatMessage.incoming("Rahim", "Hey, do you have a minute?", at(today, 10, 2)),
                        ChatMessage.outgoing("Sure, what's up?", at(today, 10, 3)),
                        ChatMessage.incoming("Rahim", "Can you review the new layout when you get a chance?", at(today, 10, 4))
                ))
                .build();
    }

    private static Conversation karim() {
        LocalDate today = LocalDate.now();
        return Conversation.builder("dm-karim", ConversationKind.DIRECT, "Karim")
                .peer(new ChatUser("Karim", Presence.ONLINE))
                .headerSubtitle(Presence.ONLINE.label())
                .sidebarSubtitle("Thanks!")
                .messages(List.of(
                        ChatMessage.outgoing("Pushed the theme tokens to the shared stylesheet.", at(today, 11, 20)),
                        ChatMessage.incoming("Karim", "Perfect, that keeps colours consistent. Thanks!", at(today, 11, 22))
                ))
                .build();
    }

    private static Conversation sakib() {
        return Conversation.builder("dm-sakib", ConversationKind.DIRECT, "Sakib")
                .peer(new ChatUser("Sakib", Presence.ONLINE))
                .headerSubtitle(Presence.ONLINE.label())
                .sidebarSubtitle("No messages yet")
                .messages(List.of())
                .build();
    }

    private static Conversation nadia() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        return Conversation.builder("dm-nadia", ConversationKind.DIRECT, "Nadia")
                .peer(new ChatUser("Nadia", Presence.AWAY))
                .headerSubtitle(Presence.AWAY.label())
                .sidebarSubtitle("See you tomorrow")
                .messages(List.of(
                        ChatMessage.incoming("Nadia", "Heading out, see you tomorrow!", at(yesterday, 18, 30)),
                        ChatMessage.outgoing("Take care!", at(yesterday, 18, 31))
                ))
                .build();
    }

    private static LocalDateTime at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute));
    }
}
