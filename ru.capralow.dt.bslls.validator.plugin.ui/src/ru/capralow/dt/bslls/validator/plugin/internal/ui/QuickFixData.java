package ru.capralow.dt.bslls.validator.plugin.internal.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Быстрое исправление BSL LS в виде строки данных маркера EDT.
 */
final class QuickFixData
{
    record Edit(int offset, int length, String newText)
    {
    }

    private static final Gson GSON = new Gson();

    final String title;
    final String description;
    final List<Edit> edits;

    QuickFixData(String title, String description, List<Edit> edits)
    {
        this.title = title;
        this.description = description;
        this.edits = edits;
    }

    static String encode(QuickFixData data)
    {
        return GSON.toJson(data);
    }

    static QuickFixData decode(String value)
    {
        if (value == null || value.isEmpty())
            return null;

        try
        {
            QuickFixData data = GSON.fromJson(value, QuickFixData.class);
            if (data == null || data.edits == null || data.edits.isEmpty())
                return null;

            return data;
        }
        catch (JsonParseException e)
        {
            return null;
        }
    }

    static boolean hasNoOverlaps(List<Edit> edits)
    {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::offset));
        for (int i = 1; i < sorted.size(); i++)
        {
            Edit previous = sorted.get(i - 1);
            if (previous.offset() + previous.length() > sorted.get(i).offset())
                return false;
        }
        return true;
    }
}
