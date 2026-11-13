package dev.xpple.seedmapper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.xpple.seedmapper.command.CommandExceptions;
import dev.xpple.seedmapper.command.arguments.ItemAndEnchantmentsPredicateArgument;
import dev.xpple.seedmapper.command.commands.LocateCommand;
import dev.xpple.seedmapper.feature.MansionLootSearch;
import dev.xpple.seedmapper.support.NativeLibrary;
import dev.xpple.seedmapper.support.VanillaMansions;
import com.github.cubiomes.Cubiomes;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

public class LocateLootCommandTest {

    private static final String VEX_TRIM = "vex_armor_trim_smithing_template";

    private static CommandDispatcher<FabricClientCommandSource> dispatcher;

    @BeforeAll
    public static void setUp() {
        NativeLibrary.load();
        VanillaMansions.bootstrap();
        dispatcher = new CommandDispatcher<>();
        LocateCommand.register(dispatcher);
    }

    private static ParseResults<FabricClientCommandSource> parse(String command) {
        return dispatcher.parse(command, null);
    }

    private static void assertParses(String command) {
        ParseResults<FabricClientCommandSource> results = parse(command);
        assertTrue(results.getExceptions().isEmpty(), () -> command + ": " + results.getExceptions());
        assertFalse(results.getReader().canRead(), () -> command + ": trailing input " + results.getReader().getRemaining());
        assertNotNull(results.getContext().getCommand(), () -> command + ": not executable");
    }

    private static void assertDoesNotParse(String command) {
        ParseResults<FabricClientCommandSource> results = parse(command);
        assertTrue(!results.getExceptions().isEmpty() || results.getReader().canRead() || results.getContext().getCommand() == null, () -> command + " should not be a valid command");
    }

    private static List<String> suggestions(String command) throws ExecutionException, InterruptedException {
        return dispatcher.getCompletionSuggestions(parse(command)).get().getList().stream().map(Suggestion::getText).toList();
    }

    @Test
    public void testVexArmorTrimIsRegistered() {
        ItemAndEnchantmentsPredicateArgument.EnchantedItem item = ItemAndEnchantmentsPredicateArgument.getItem(VEX_TRIM);
        assertNotNull(item);
        assertEquals("minecraft:" + VEX_TRIM, Cubiomes.global_id2item_name(item.item(), Cubiomes.MC_NEWEST()).getString(0));
        assertEquals(item.item(), Cubiomes.item_name2global_id(java.lang.foreign.Arena.global().allocateFrom("minecraft:" + VEX_TRIM)));
        assertEquals(LocateCommand.DEFAULT_MANSION_LOOT_ITEM, VEX_TRIM);
        assertTrue(LocateCommand.LOOT_SUPPORTED_STRUCTURES.contains(Cubiomes.Mansion()));
    }

    @Test
    public void testItemArgument() throws CommandSyntaxException {
        ItemAndEnchantmentsPredicateArgument argument = ItemAndEnchantmentsPredicateArgument.itemAndEnchantments();
        int expected = ItemAndEnchantmentsPredicateArgument.getItem(VEX_TRIM).item();

        assertEquals(expected, argument.parse(new StringReader(VEX_TRIM)).item());
        assertEquals(expected, argument.parse(new StringReader("minecraft:" + VEX_TRIM)).item());

        // the existing syntax keeps working, with and without namespace
        int book = argument.parse(new StringReader("book")).item();
        assertEquals(book, argument.parse(new StringReader("minecraft:book with mending *")).item());
        assertEquals(book, argument.parse(new StringReader("book with mending 1 without unbreaking 2")).item());
        assertEquals("minecraft:diamond_pickaxe", Cubiomes.global_id2item_name(argument.parse(new StringReader("diamond_pickaxe with efficiency 3")).item(), Cubiomes.MC_NEWEST()).getString(0));

        for (String invalid : List.of("vex_armor_trim", "minecraft:not_an_item", "other:" + VEX_TRIM, "minecraft:", ":" + VEX_TRIM, "")) {
            CommandSyntaxException exception = assertThrows(CommandSyntaxException.class, () -> argument.parse(new StringReader(invalid)), invalid);
            assertSame(CommandExceptions.UNKNOWN_ITEM_EXCEPTION, exception.getType(), invalid);
        }
        CommandSyntaxException exception = assertThrows(CommandSyntaxException.class, () -> argument.parse(new StringReader(VEX_TRIM + " with not_an_enchantment 1")));
        assertSame(CommandExceptions.UNKNOWN_ENCHANTMENT_EXCEPTION, exception.getType());
    }

    @Test
    public void testLootCommandSyntax() {
        assertParses("sm:locate loot 5 " + VEX_TRIM);
        assertParses("sm:locate loot 5 minecraft:" + VEX_TRIM);
        assertParses("sm:locate loot 1 enchanted_golden_apple");
        assertParses("sm:locate loot 3 book with mending *");
        assertParses("sm:locate loot 3 minecraft:book with mending 1 without unbreaking 3");
        assertDoesNotParse("sm:locate loot 5 not_an_item");
        assertDoesNotParse("sm:locate loot 0 " + VEX_TRIM);
        assertDoesNotParse("sm:locate loot " + VEX_TRIM);
    }

    @Test
    public void testMansionLootCommandSyntax() {
        assertParses("sm:locate mansionloot 5");
        assertParses("sm:locate mansionloot 5 20000");
        assertParses("sm:locate mansionloot 5 " + MansionLootSearch.MAX_RADIUS);
        assertParses("sm:locate mansionloot 2 20000 " + VEX_TRIM);
        assertParses("sm:locate mansionloot 2 20000 minecraft:enchanted_golden_apple");
        assertParses("sm:locate mansionloot 1 20000 book with mending *");
        assertDoesNotParse("sm:locate mansionloot");
        assertDoesNotParse("sm:locate mansionloot 0");
        assertDoesNotParse("sm:locate mansionloot 5 0");
        assertDoesNotParse("sm:locate mansionloot 5 " + (MansionLootSearch.MAX_RADIUS + 1));
        assertDoesNotParse("sm:locate mansionloot 5 20000 not_an_item");
        assertDoesNotParse("sm:locate mansionloot 5 " + VEX_TRIM);
    }

    @Test
    public void testOtherLocateCommandsStillParse() {
        assertParses("sm:locate biome dark_forest");
        assertParses("sm:locate feature mansion");
        assertParses("sm:locate feature stronghold");
        assertParses("sm:locate slimechunk");
        assertParses("sm:locate spawn");
        assertParses("sm:locate orevein copper");
        assertParses("sm:locate canyon");
    }

    @Test
    public void testSuggestions() throws ExecutionException, InterruptedException {
        assertTrue(suggestions("sm:locate loot 5 vex").contains(VEX_TRIM));
        assertEquals(List.of("minecraft:" + VEX_TRIM), suggestions("sm:locate loot 5 minecraft:vex"));
        assertTrue(suggestions("sm:locate loot 5 ").contains(VEX_TRIM));
        assertTrue(suggestions("sm:locate mansionloot 5 20000 vex").contains(VEX_TRIM));
        assertTrue(suggestions("sm:locate ").contains("mansionloot"));
        // conditions on enchantments are still suggested after the item
        assertEquals(List.of("with", "without"), suggestions("sm:locate loot 5 minecraft:book "));
    }
}
