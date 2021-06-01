package com.alphawallet.app.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.alphawallet.app.R;
import com.alphawallet.app.entity.tokens.Token;
import com.alphawallet.app.repository.TokenRepository;
import com.alphawallet.app.repository.TokensRealmSource;
import com.alphawallet.app.repository.entity.RealmGasSpread;
import com.alphawallet.app.repository.entity.RealmToken;
import com.alphawallet.app.repository.entity.RealmTokenTicker;
import com.alphawallet.app.service.AssetDefinitionService;
import com.alphawallet.app.service.TickerService;
import com.alphawallet.app.service.TokensService;
import com.alphawallet.app.ui.widget.entity.AmountReadyCallback;
import com.alphawallet.app.ui.widget.entity.NumericInput;
import com.alphawallet.app.util.BalanceUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import io.realm.Case;
import io.realm.Realm;
import io.realm.RealmQuery;
import io.realm.Sort;

import static com.alphawallet.app.C.GAS_LIMIT_MIN;
import static com.alphawallet.app.repository.TokensRealmSource.databaseKey;

/**
 * Created by JB on 10/11/2020.
 */
public class InputAmount extends LinearLayout
{
    private final Context context;
    private final NumericInput editText;
    private final TextView symbolText;
    private final TokenIcon icon;
    private final ChainName chainName;
    private final TextView availableSymbol;
    private final TextView availableAmount;
    private final TextView allFunds;
    private final ProgressBar gasFetch;
    private Token token;
    private Realm realm;
    private Realm tickerRealm;
    private TokensService tokensService;
    private AssetDefinitionService assetService;
    private BigInteger gasPriceEstimate = BigInteger.ZERO;
    private BigDecimal exactAmount = BigDecimal.ZERO;
    private final Handler handler = new Handler();
    private AmountReadyCallback amountReadyCallback;
    private boolean amountReady;

    //These need to be members because the listener is shut down if the object doesn't exist
    private RealmTokenTicker realmTickerUpdate;
    private RealmToken realmTokenUpdate;

    private boolean showingCrypto;

    public InputAmount(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        this.context = context;
        inflate(context, R.layout.item_input_amount, this);

        editText = findViewById(R.id.amount_entry);
        symbolText = findViewById(R.id.text_token_symbol);
        icon = findViewById(R.id.token_icon);
        chainName = findViewById(R.id.chain_name);
        availableSymbol = findViewById(R.id.text_symbol);
        availableAmount = findViewById(R.id.text_available);
        allFunds = findViewById(R.id.text_all_funds);
        gasFetch = findViewById(R.id.gas_fetch_progress);
        showingCrypto = true;
        amountReady = false;

        setupAttrs(context, attrs);

        setupViewListeners();
    }

    /**
     * Initialise the component. Note that it will still work if assetDefinitionService is null, however some tokens (notably ERC721) may not show correctly if it is null.
     * Perhaps the token icon info should go into the TokensService not the AssetDefinitionService?
     *
     * @param token
     * @param assetDefinitionService
     * @param svs
     */
    public void setupToken(@NotNull Token token, @Nullable AssetDefinitionService assetDefinitionService,
                           @NotNull TokensService svs, @NotNull AmountReadyCallback amountCallback)
    {
        this.token = token;
        this.tokensService = svs;
        this.assetService = assetDefinitionService;
        this.amountReadyCallback = amountCallback;
        icon.bindData(token, assetService);
        chainName.setChainID(token.tokenInfo.chainId);
        updateAvailableBalance();

        this.realm = tokensService.getWalletRealmInstance();
        this.tickerRealm = tokensService.getTickerRealmInstance();
        bindDataSource();
        setupAllFunds();
    }

    public void getInputAmount()
    {
        if (gasFetch.getVisibility() == View.VISIBLE)
        {
            amountReady = true;
        }
        else
        {
            //immediate return
            if (exactAmount.compareTo(BigDecimal.ZERO) > 0)
            {
                amountReadyCallback.amountReady(exactAmount, new BigDecimal(gasPriceEstimate)); //'All Funds', must include gas Price
            }
            else
            {
                amountReadyCallback.amountReady(getWeiInputAmount(), BigDecimal.ZERO);
            }
        }
    }

    public void onDestroy()
    {
        if (realm != null) realm.removeAllChangeListeners();
        if (tickerRealm != null) tickerRealm.removeAllChangeListeners();
        realmTickerUpdate = null;
        realmTokenUpdate = null;
    }

    public void setAmount(String ethAmount)
    {
        exactAmount = BigDecimal.ZERO;
        editText.setText(ethAmount);
    }

    public void showError(boolean showError, int customError)
    {
        TextView errorText = findViewById(R.id.text_error);
        if (customError != 0)
        {
            errorText.setText(customError);
        }
        else
        {
            errorText.setText(String.format(getResources().getString(R.string.error_insufficient_funds), token.getSymbolOrShortName()));
        }

        if (showError)
        {
            errorText.setVisibility(View.VISIBLE);
            editText.setTextColor(context.getColor(R.color.danger));
        }
        else
        {
            errorText.setVisibility(View.GONE);
            editText.setTextColor(context.getColor(R.color.text_dark_gray));
        }

    }

    private void updateAvailableBalance()
    {
        if (showingCrypto)
        {
            showCrypto();
        }
        else
        {
            showFiat();
        }
    }

    /**
     * Setup realm binding for token balance updates
     */
    private void bindDataSource()
    {
        realmTokenUpdate = realm.where(RealmToken.class)
                .equalTo("address", databaseKey(token.tokenInfo.chainId, token.tokenInfo.address.toLowerCase()), Case.INSENSITIVE)
                .findFirstAsync();

        realmTokenUpdate.addChangeListener(realmToken -> {
            //load token & update balance
            RealmToken rt = (RealmToken)realmToken;
            token = tokensService.getToken(rt.getChainId(), rt.getTokenAddress());
            updateAvailableBalance();
        });
    }

    private void setupViewListeners()
    {
        LinearLayout clickMore = findViewById(R.id.layout_more_click);

        clickMore.setOnClickListener(v -> {
            //on down caret clicked - switch to fiat currency equivalent if there's a ticker
            if (getTickerQuery() == null) return;

            RealmTokenTicker rtt = getTickerQuery().findFirst();
            if (showingCrypto && rtt != null)
            {
                showingCrypto = false;
                startTickerListener();
                icon.showLocalCurrency();
            }
            else
            {
                showingCrypto = true;
                if (tickerRealm != null) tickerRealm.removeAllChangeListeners(); //stop ticker listener
                icon.bindData(token, assetService);
            }

            updateAvailableBalance();
        });

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (editText.hasFocus())
                {
                    exactAmount = BigDecimal.ZERO; //invalidate the 'all funds' amount
                    showError(false, 0);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (editText.hasFocus())
                {
                    amountReadyCallback.updateCryptoAmount(getWeiInputAmount());
                }
            }
        });

        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus)
            {
                showError(false, 0);
            }
        });

        editText.setOnClickListener(v -> {
            showError(false, 0);
        });
    }

    private RealmQuery<RealmTokenTicker> getTickerQuery()
    {
        if (tickerRealm != null)
        {
            return tickerRealm.where(RealmTokenTicker.class)
                    .equalTo("contract", TokensRealmSource.databaseKey(token.tokenInfo.chainId, token.isEthereum() ? "eth" : token.getAddress().toLowerCase()));
        }
        else
        {
            return null;
        }
    }

    private void startTickerListener()
    {
        if (getTickerQuery() == null) return;
        realmTickerUpdate = getTickerQuery().findFirstAsync();
        realmTickerUpdate.addChangeListener(realmTicker -> {
            updateAvailableBalance();
        });
    }

    private void showCrypto()
    {
        symbolText.setText(token.getSymbol());
        availableSymbol.setText(token.getSymbol());
        availableAmount.setText(token.getStringBalance());
        updateAllFundsAmount();
    }

    private void showFiat()
    {
        try
        {
            RealmTokenTicker rtt = getTickerQuery().findFirst();

            if (rtt != null)
            {
                String currencyLabel = rtt.getCurrencySymbol() + TickerService.getCurrencySymbol();
                symbolText.setText(currencyLabel);
                //calculate available fiat
                double cryptoRate = Double.parseDouble(rtt.getPrice());
                double availableCryptoBalance = Double.parseDouble(token.getStringBalance());
                availableAmount.setText(TickerService.getCurrencyString(availableCryptoBalance * cryptoRate));
                availableSymbol.setText(rtt.getCurrencySymbol());
                updateAllFundsAmount(); //update amount if showing 'All Funds'

                amountReadyCallback.updateCryptoAmount(
                        getWeiInputAmount()
                ); //now update
            }
        }
        catch (Exception e)
        {
            // continue with old value
        }
    }

    private BigDecimal getWeiInputAmount()
    {
        BigDecimal inputVal = editText.getBigDecimalValue();
        //get wei value
        if (inputVal.equals(BigDecimal.ZERO))
        {
            return inputVal;
        }
        else if (showingCrypto)
        {
            return inputVal.multiply(BigDecimal.valueOf(Math.pow(10, token.tokenInfo.decimals)));
        }
        else
        {
            return convertFiatAmountToWei(inputVal.doubleValue());
        }
    }

    /**
     * Setting up the 'All Funds' button
     */
    private void setupAllFunds()
    {
        allFunds.setOnClickListener(v -> {
            if (token.isEthereum() && token.hasPositiveBalance())
            {
                RealmGasSpread gasSpread = tokensService.getTickerRealmInstance().where(RealmGasSpread.class)
                            .equalTo("chainId", token.tokenInfo.chainId)
                            .sort("timeStamp", Sort.DESCENDING)
                            .findFirst();

                if (gasSpread != null && gasSpread.getGasPrice().standard.compareTo(BigInteger.ZERO) > 0)
                {
                    //assume 'average' gas cost here
                    onLatestGasPrice(gasSpread.getGasPrice().standard);
                }
                else //fallback to node price
                {
                    gasFetch.setVisibility(View.VISIBLE);
                    Web3j web3j = TokenRepository.getWeb3jService(token.tokenInfo.chainId);
                    web3j.ethGasPrice().sendAsync()
                            .thenAccept(ethGasPrice -> onLatestGasPrice(ethGasPrice.getGasPrice()))
                            .exceptionally(this::onGasFetchError);
                }
            }
            else
            {
                editText.setText(token.getStringBalance());
            }
        });
    }

    private void onLatestGasPrice(BigInteger price)
    {
        gasPriceEstimate = price;
        //calculate max amount possible
        BigDecimal networkFee = new BigDecimal(gasPriceEstimate.multiply(BigInteger.valueOf(GAS_LIMIT_MIN)));
        exactAmount = token.balance.subtract(networkFee);
        if (exactAmount.compareTo(BigDecimal.ZERO) < 0) exactAmount = BigDecimal.ZERO;
        //display in the view
        handler.post(updateValue);
    }

    private final Runnable updateValue = new Runnable()
    {
        @Override
        public void run()
        {
            gasFetch.setVisibility(View.GONE);
            updateAllFundsAmount();

            if (amountReady)
            {
                amountReadyCallback.amountReady(exactAmount, new BigDecimal(gasPriceEstimate));
                amountReady = false;
            }
        }
    };

    private void setupAttrs(Context context, AttributeSet attrs)
    {
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.InputView,
                0, 0
        );

        try
        {
            boolean showHeader = a.getBoolean(R.styleable.InputView_show_header, true);
            boolean showAllFunds = a.getBoolean(R.styleable.InputView_show_allFunds, true);
            int headerTextId = a.getResourceId(R.styleable.InputView_label, R.string.amount);
            findViewById(R.id.layout_header_amount).setVisibility(showHeader ? View.VISIBLE : View.GONE);
            allFunds.setVisibility(showAllFunds ? View.VISIBLE : View.GONE);
            TextView headerText = findViewById(R.id.text_header);
            headerText.setText(headerTextId);
        }
        finally
        {
            a.recycle();
        }
    }

    private Void onGasFetchError(Throwable throwable)
    {
        gasFetch.setVisibility(View.GONE);
        return null;
    }

    private String convertWeiAmountToFiat(BigDecimal value)
    {
        String fiatValue = "0";
        try
        {
            RealmTokenTicker rtt = getTickerQuery().findFirst();

            if (rtt != null)
            {
                double cryptoRate = Double.parseDouble(rtt.getPrice());
                double availableCryptoBalance = value.divide(BigDecimal.valueOf(Math.pow(10, token.tokenInfo.decimals)), 18, RoundingMode.DOWN).doubleValue();
                DecimalFormat df = new DecimalFormat("#,##0.00");
                df.setRoundingMode(RoundingMode.DOWN);
                fiatValue = df.format(availableCryptoBalance * cryptoRate);
            }
        }
        catch (Exception e)
        {
            // continue with old value
        }

        return fiatValue;
    }

    private BigDecimal convertFiatAmountToWei(double fiatAmount)
    {
        BigDecimal weiAmount = BigDecimal.ZERO;
        try
        {
            RealmTokenTicker rtt = getTickerQuery().findFirst();

            if (rtt != null)
            {
                double wei = fiatAmount / Double.parseDouble(rtt.getPrice());
                weiAmount = BigDecimal.valueOf(wei).multiply(BigDecimal.valueOf(Math.pow(10, token.tokenInfo.decimals)));
            }
        }
        catch (Exception e)
        {
            // continue with old value
        }

        return weiAmount;
    }

    /**
     * After user clicked on 'All Funds' and we calculated the exactAmount which is the largest value (minus gas fee) the account can support
     */
    private void updateAllFundsAmount()
    {
        if (exactAmount.compareTo(BigDecimal.ZERO) > 0)
        {
            String showValue = "";
            if (showingCrypto)
            {
                showValue = BalanceUtils.getScaledValueScientific(exactAmount, token.tokenInfo.decimals);
            }
            else
            {
                showValue = convertWeiAmountToFiat(exactAmount);
            }

            editText.setText(showValue);
        }
    }
}
