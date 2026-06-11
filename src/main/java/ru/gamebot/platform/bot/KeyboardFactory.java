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

        for (InlineKeyboardButton button : buttons) {
            if (button == null) {
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

    private boolean isShort(String text) {
        return text != null && text.length() < 14;
    }
}
