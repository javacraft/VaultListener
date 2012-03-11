import java.io.*;
import java.util.Properties;
import java.util.logging.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import com.vexsoftware.votifier.Votifier;
import com.vexsoftware.votifier.model.*;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;


/**
 * VaultListener is a listener class for Votifier 1.4 using Vault as a common API to various economy plugins and allows
 * admins to reward their players with virtual cash for voting. The original intent of this class was to provide missing
 * iConomy 6 support to Votifier; however, by using Vault it has the capabilities of using iConomy 4, iConomy 5, iConomy 6,
 * BOSEconomy 6, BOSEconomy 7, EssentialEcon, 3Co, and MultiConomy. For more economy options, please see the Vault plugin on
 * BukkitDev - http://dev.bukkit.org/server-mods/vault/.
 * 
 * For installation and configuration, please see the accompanying README file.
 * 
 * The methodology and approach used herein is largely based upon the original iConomyListener written by Blake Beaupin.
 * VaultListener improves thereupon by incorporating the Vault API, customized/colored messages, additional fault
 * tolerances and checks, and file resource optimizations to improve the general stability and reportability of this listener.
 * 
 * @author frelling
 * 
 */
public class VaultListener implements VoteListener {
	private static String	version		= "1.1.2";
	private static Logger	logger		= Logger.getLogger( "VaultListener" );
	private static String	PROP_FILE	= "VaultListener.properties";
	private static String	VL_ID		= "[Votifier][VaultListener " + version + "]";

	// Reward amount
	private static String	PK_AMT		= "reward_amount";
	private static String	DEF_AMT		= "30.00";
	private double			amount		= 30.0;
	private double			paid		= amount;
	
	// Reward adornment
	private static String	PK_PREFIX	= "reward_prefix";
	private String			prefix		= "";
	
	private static String	PK_SUFFIX	= "reward_suffix";
	private String			suffix		= " USD";
	
	// Reward type
	private static String	PK_TYPE		= "reward_type";
	private static String	TYPE_FIXED	= "fixed";
	private static String	TYPE_RATE	= "rate";
	private static boolean	isRate		= false;
	
	// Reward rate
	private static String	PK_RATE		= "reward_rate";
	private static String	DEF_RATE	= "0.01";
	private double			rate		= 0.01;

	// Vote confirmation message
	private static String	PK_VMSG		= "confirm_msg";
	private String			confirmMsg	= "Thanks {IGN}, for voting on {SERVICE}!";

	// Payment confirmation message
	private static String	PK_PMSG		= "payment_msg";
	private String			paymentMsg	= "{AMOUNT} has been added to your {ECONOMY} balance.";

	// Debug Flag
	private static String	PK_DEBUG	= "debug";
	private boolean			debug		= false;
	
	// Broadcast message
	private static String	PK_BCAST	= "broadcast";
	private boolean			bCastFlag	= true;
	private static String	PK_BCASTMSG	= "broadcast_msg";
	private String			bCastMsg	= "The server was voted for by {IGN}!";

	private Economy			econ		= null;
	private Votifier		plugin		= null;


	/**
	 * Constructor - Initialize properties and economy API
	 */
	public VaultListener() {
		plugin = Votifier.getInstance();
		if ( plugin != null ) {
			initializeProperties();
			initializeEconomyAPI();
		}
		else
			vlLog( Level.SEVERE, "Cannot find reference to Votifier plugin!" );
	}


	/**
	 * Initialize VaultListener properties. Property file is expected to reside in Votifier's data directory. If not found, a
	 * default property file is generated.
	 */
	private void initializeProperties() {
		Properties props = new Properties();
		File propFile = new File( plugin.getDataFolder(), PROP_FILE );

		if ( propFile.exists() ) {
			/*
			 * Read property file if found.
			 */
			try {
				FileReader freader = new FileReader( propFile );
				props.load( freader );
				freader.close();
			}
			catch ( IOException ex ) {
				vlLog( Level.WARNING,
						"Error loading VaultListener properties. Using default messages and reward of "
								+ DEF_AMT );
			}
		}
		else {
			/*
			 * Create property file if it wasn't found.
			 */
			vlLogInfo( "No VaultListener properties file found, creating default file." );
			try {
				propFile.createNewFile();
				props.setProperty( PK_AMT, Double.toString( amount ) );
				props.setProperty( PK_TYPE, TYPE_FIXED );
				props.setProperty( PK_RATE, Double.toString( rate ) );
				props.setProperty( PK_VMSG, confirmMsg );
				props.setProperty( PK_PMSG, paymentMsg );
				props.setProperty( PK_BCAST, ""+bCastFlag );
				props.setProperty( PK_BCASTMSG, bCastMsg );
				props.setProperty( PK_PREFIX, prefix );
				props.setProperty( PK_SUFFIX, suffix );


				FileWriter fwriter = new FileWriter( propFile );
				props.store( fwriter, "Vault Listener Properties" );
				fwriter.close();

			}
			catch ( IOException ex ) {
				vlLog( Level.WARNING, "Error creating VaultListener properties." );
			}
		}
		
		// Read reward amount. Use default amount if illegal number.
		try {
			amount = Double.parseDouble( props.getProperty( PK_AMT, DEF_AMT ) );
		}
		catch ( NumberFormatException ex ) {
			amount = Double.parseDouble( DEF_AMT );
			vlLog( Level.WARNING, "Illegal reward_amount! Using default reward of " + DEF_AMT );
		}
		
		isRate = props.getProperty(  PK_TYPE, TYPE_FIXED ).toLowerCase().equals( TYPE_RATE );
		
		// Read reward rate. Use default rate if illegal number.
		if ( isRate ) {
			try {
				rate = Double.parseDouble( props.getProperty( PK_RATE, DEF_RATE ) );
			}
			catch ( NumberFormatException ex ) {
				rate = Double.parseDouble( DEF_RATE );
				vlLog( Level.WARNING, "Illegal reward_rate! Using default rate of " + DEF_RATE );
			}
		}

		prefix = props.getProperty( PK_PREFIX, prefix );
		suffix = props.getProperty( PK_SUFFIX, suffix );
		confirmMsg = props.getProperty( PK_VMSG, confirmMsg );
		paymentMsg = props.getProperty( PK_PMSG, paymentMsg );
		debug = Boolean.parseBoolean( props.getProperty( PK_DEBUG, "false" ) );
		bCastFlag = Boolean.parseBoolean( props.getProperty( PK_BCAST, "true" ) );
		bCastMsg = props.getProperty( PK_BCASTMSG, bCastMsg );
	}


	/**
	 * Initialize economy API. Note: Because this listener is just a simple class and has no control over how Votifier loads,
	 * it is impossible to specify a soft/hard dependency to ensure that Vault loads before Votifier. Fortunately, Bukkit's
	 * two pass class loading approach should take care of this.
	 */
	private void initializeEconomyAPI() {
		RegisteredServiceProvider<Economy> economyProvider = null;
		try {
			economyProvider = plugin.getServer().getServicesManager()
					.getRegistration( net.milkbowl.vault.economy.Economy.class );
		}
		catch ( NoClassDefFoundError ex ) {
			vlLog( Level.SEVERE,
					"Could not find Vault API. Please make sure Vault is installed and enabled!" );
		}

		if ( economyProvider != null ) {
			econ = economyProvider.getProvider();
			vlLogInfo( "Using economy plugin: " + econ.getName() );
		}
		else {
			econ = null;
			vlLog( Level.WARNING,
					"Vault cannot detect a valid economy plugin. No payments will be made!" );
		}
	}


	@Override
	public void voteMade( Vote vote ) {
		String ign = vote.getUsername();
		vlLogInfo( ign );

		if ( debug ) {
			vlLogInfo( "Vote notification received, dumping messages..." );
			vlLogInfo( insertTokenData( vote, confirmMsg ) );
			vlLogInfo( insertTokenData( vote, paymentMsg ) );
		}

		Player player = plugin.getServer().getPlayerExact( vote.getUsername() );
		
		if ( bCastFlag )
			plugin.getServer().broadcastMessage( insertTokenData( vote, bCastMsg ) );

		// Thank the player, if online
		if ( player != null ) {
			player.sendMessage( insertTokenData( vote, confirmMsg ) );
		}

		// Try to pay player
		if ( econ != null ) {
			/*
			 * If reward_type is 'rate' calculate percentage of player's balance. If it is
			 * less than the fixed amount, pay the fixed amount instead.
			 */
			if ( isRate ) {
				paid = econ.getBalance( ign ) * rate;
				if ( paid < amount )
					paid = amount;
			}
			else
				paid = amount;
			
			paid = Math.round( 100.0 * paid ) / 100.0;
			EconomyResponse eres = econ.depositPlayer( ign, paid );
			if ( eres.type != ResponseType.FAILURE ) {
				// Send payment confirmation, if online
				if ( player != null ) {
					player.sendMessage( insertTokenData( vote, paymentMsg ) );
				}
			}
			else {
				vlLogInfo( eres.errorMessage );
			}
		}
	}


	private static void vlLog( Level lvl, String msg ) {
		logger.log( lvl, VL_ID + " " + msg );
	}


	private static void vlLogInfo( String msg ) {
		vlLog( Level.INFO, msg );
	}


	/*
	 * Replace token values in given string with actual data.
	 */
	private String insertTokenData( Vote vote, String str ) {
		String msg = str.replace( "{SERVICE}", vote.getServiceName() );
		msg = msg.replace( "{IGN}", vote.getUsername() );
		msg = msg.replace( "{AMOUNT}", prefix + Double.toString( paid ) + suffix );
		msg = msg.replace( "{ECONOMY}", (econ != null) ? econ.getName()
				: "UNKNOWN" );
		msg = msg.replaceAll( "(?i)&([0-9A-F])", "\u00A7$1" );
		return msg;
	}

}
