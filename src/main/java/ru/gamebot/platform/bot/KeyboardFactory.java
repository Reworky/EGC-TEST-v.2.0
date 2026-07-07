package ru.gamebot.platform.bot;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Component
public class KeyboardFactory {

    public InlineKeyboardMarkup smartLayout(List<InlineKeyboardButton> buttons) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> pair = new ArrayList<>(2);
        InlineKeyboardButton menuButton = null;

        for (InlineKeyboardButton button : buttons) {
            if (button == null) {
                continue;
            }
            if (isMenu(button)) {
                menuButton = button;
                continue;
            }
            if (isShort(button.getText())) {
                pair.add(button);
                if (pair.size() == 2) {
                    rows.add(new ArrayList<>(pair));
                    pair.clear();
                }
            } else {
                if (!pair.isEmpty()) {
                    rows.add(new ArrayList<>(pair));
                    pair.clear();
                }
                rows.add(List.of(button));
            }
        }

        if (!pair.isEmpty()) {
            rows.add(new ArrayList<>(pair));
        }
        if (menuButton != null) {
            rows.add(List.of(menuButton));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    public InlineKeyboardMarkup verticalLayout(List<InlineKeyboardButton> buttons) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        InlineKeyboardButton menuButton = null;
        for (InlineKeyboardButton button : buttons) {
            if (button == null) {
                continue;
            }
            if (isMenu(button)) {
                menuButton = button;
                continue;
            }
            rows.add(List.of(button));
        }
        if (menuButton != null) {
            rows.add(List.of(menuButton));
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    public InlineKeyboardMarkup rowsLayout(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    public InlineKeyboardButton callback(String text, String data) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(data);
        return button;
    }

    public InlineKeyboardButton url(String text, String url) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setUrl(url);
        return button;
    }

    public InlineKeyboardButton webApp(String text, String url) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setWebApp(new org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo(url));
        return button;
    }

    private boolean isShort(String text) {
        return text != null && text.length() < 14;
    }

    private boolean isMenu(InlineKeyboardButton button) {
        return "🏠 Меню".equals(button.getText());
    }
}
