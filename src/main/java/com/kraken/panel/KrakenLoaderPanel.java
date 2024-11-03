package com.kraken.panel;

import com.google.inject.Inject;
import com.google.inject.Provider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.MultiplexingPluginPanel;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.Text;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class KrakenLoaderPanel extends PluginPanel {

	private static final String RUNELITE_GROUP_NAME = RuneLiteConfig.class.getAnnotation(ConfigGroup.class).value();
	private static final String PINNED_PLUGINS_CONFIG_KEY = "krakenPinnedPlugins";

    private List<KrakenPluginListItem> pluginList;
    private final JPanel display = new JPanel();
    private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);
    private final IconTextField searchBar;
    private final JScrollPane scrollPane;
    private final FixedWidthPanel mainPanel;

    private final ConfigManager configManager;
    private final List<PluginMetadata> fakePlugins = new ArrayList<>();
    private final Provider<ConfigPanel> configPanelProvider;
	private final PluginManager pluginManager;

    @Getter
	private final MultiplexingPluginPanel muxer;

    @Inject
    public KrakenLoaderPanel(EventBus eventBus,
                             PluginManager pluginManager,
                             ConfigManager configManager,
                             Provider<ConfigPanel> configPanelProvider) {
        super(false);

        this.configManager = configManager;
        this.pluginManager = pluginManager;
        this.configPanelProvider = configPanelProvider;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));

        this.muxer = new MultiplexingPluginPanel(this) {
			@Override
			protected void onAdd(PluginPanel p) {
				eventBus.register(p);
			}

			@Override
			protected void onRemove(PluginPanel p) {
				eventBus.unregister(p);
			}
		};

        searchBar = new IconTextField();
        searchBar.setIcon(IconTextField.Icon.SEARCH);
        searchBar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 20, 30));
        searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
        searchBar.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onSearchBarChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onSearchBarChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onSearchBarChanged();
            }
        });


        JPanel topPanel = new JPanel();
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        topPanel.setLayout(new BorderLayout(0, BORDER_OFFSET));
        topPanel.add(searchBar, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        mainPanel = new FixedWidthPanel();
        mainPanel.setBorder(new EmptyBorder(8, 10, 10, 10));
        mainPanel.setLayout(new DynamicGridLayout(0, 1, 0, 5));
        mainPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel northPanel = new FixedWidthPanel();
        northPanel.setLayout(new BorderLayout());
        northPanel.add(mainPanel, BorderLayout.NORTH);

        scrollPane = new JScrollPane(northPanel);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void rebuildPluginList() {
		final List<String> pinnedPlugins = getPinnedPluginNames();

		// populate pluginList with all non-hidden plugins
		pluginList = Stream.concat(
			fakePlugins.stream(),
			pluginManager.getPlugins().stream()
                // TODO Also filter for Kraken plugins. Need a better way to identify Kraken plugins.
				.filter(plugin -> {
					return !plugin.getClass().getAnnotation(PluginDescriptor.class).hidden() &&
							plugin.getName().equals("Alchemical Hydra");
				})
				.map(plugin ->
				{
					PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
					Config config = pluginManager.getPluginConfigProxy(plugin);
					ConfigDescriptor configDescriptor = config == null ? null : configManager.getConfigDescriptor(config);
					List<String> conflicts = pluginManager.conflictsForPlugin(plugin).stream()
						.map(Plugin::getName)
						.collect(Collectors.toList());

					return new PluginMetadata(
						descriptor.name(),
						descriptor.description(),
						descriptor.tags(),
						plugin,
						config,
						configDescriptor,
						conflicts);
				})
		)
			.map(desc ->
			{
				KrakenPluginListItem listItem = new KrakenPluginListItem(this, desc);
				listItem.setPinned(pinnedPlugins.contains(desc.getName()));
				return listItem;
			})
			.sorted(Comparator.comparing(p -> p.getPluginConfig().getName()))
			.collect(Collectors.toList());

		mainPanel.removeAll();
		refresh();
	}

	void refresh() {
		// update enabled / disabled status of all items
		pluginList.forEach(listItem -> {
			final Plugin plugin = listItem.getPluginConfig().getPlugin();
			if (plugin != null) {
				listItem.setPluginEnabled(pluginManager.isPluginEnabled(plugin));
			}
			mainPanel.add(listItem);
		});

		int scrollBarPosition = scrollPane.getVerticalScrollBar().getValue();

		onSearchBarChanged();
		searchBar.requestFocusInWindow();
		validate();

		scrollPane.getVerticalScrollBar().setValue(scrollBarPosition);
	}

	void openWithFilter(String filter) {
		searchBar.setText(filter);
		onSearchBarChanged();
		muxer.pushState(this);
	}

    private void onSearchBarChanged() {
        final String text = searchBar.getText();
//        pluginList.forEach(mainPanel::remove);
//        PluginSearch.search(pluginList, text).forEach(mainPanel::add);
        revalidate();
    }

    public void openConfigurationPanel(String configGroup) {
		for (KrakenPluginListItem pluginListItem : pluginList) {
			if (pluginListItem.getPluginConfig().getName().equals(configGroup)) {
				openConfigurationPanel(pluginListItem.getPluginConfig());
				break;
			}
		}
	}

    public void openConfigurationPanel(Plugin plugin) {
		for (KrakenPluginListItem pluginListItem : pluginList) {
			if (pluginListItem.getPluginConfig().getPlugin() == plugin) {
				openConfigurationPanel(pluginListItem.getPluginConfig());
				break;
			}
		}
	}

	private List<String> getPinnedPluginNames() {
		final String config = configManager.getConfiguration(RUNELITE_GROUP_NAME, PINNED_PLUGINS_CONFIG_KEY);
		if (config == null) {
			return Collections.emptyList();
		}

		return Text.fromCSV(config);
	}

	void savePinnedPlugins() {
		final String value = pluginList.stream()
			.filter(KrakenPluginListItem::isPinned)
			.map(p -> p.getPluginConfig().getName())
			.collect(Collectors.joining(","));

		configManager.setConfiguration(RUNELITE_GROUP_NAME, PINNED_PLUGINS_CONFIG_KEY, value);
	}


	public void openConfigurationPanel(PluginMetadata metadata) {
		ConfigPanel panel = configPanelProvider.get();
		panel.init(metadata);
		muxer.pushState(this);
		muxer.pushState(panel);
	}

    public void startPlugin(Plugin plugin) {
        pluginManager.setPluginEnabled(plugin, true);
        try {
            pluginManager.startPlugin(plugin);
        } catch (PluginInstantiationException e) {
            log.error("Failed to start plugin: {}. Error = {}", plugin.getName(), e.getMessage());
            e.printStackTrace();
        }
    }

    public void stopPlugin(Plugin plugin) {
        pluginManager.setPluginEnabled(plugin, false);
        try {
            pluginManager.stopPlugin(plugin);
        } catch (PluginInstantiationException e) {
            log.error("Failed to stop plugin: {}. Error = {}", plugin.getName(), e.getMessage());
            e.printStackTrace();
        }
    }

	@Override
	public Dimension getPreferredSize() {
		return new Dimension(PANEL_WIDTH + SCROLLBAR_WIDTH, super.getPreferredSize().height);
	}

	@Override
	public void onActivate() {
		super.onActivate();
		if (searchBar.getParent() != null) {
			searchBar.requestFocusInWindow();
		}
	}

	@Subscribe
	private void onExternalPluginsChanged(ExternalPluginsChanged ev) {
		SwingUtilities.invokeLater(this::rebuildPluginList);
	}

	@Subscribe
	private void onProfileChanged(ProfileChanged ev) {
		SwingUtilities.invokeLater(this::rebuildPluginList);
	}

}
