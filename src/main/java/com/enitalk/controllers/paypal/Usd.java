/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.enitalk.controllers.paypal;

import com.ximpleware.AutoPilot;
import com.ximpleware.VTDGen;
import com.ximpleware.VTDNav;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.client.fluent.Request;

/**
 *
 * @author kraav
 */
public class Usd {

    public static void main(String[] args) throws IOException, ParseException {

        String rs = Request.Get("http://www.cbr.ru/scripts/XML_daily.asp").addHeader("Content-type", "application/xml;charset=utf-8")
                .execute().returnContent().asString(Charset.forName("windows-1251"));

        Pair<AutoPilot, VTDNav> bb = getNavigator(rs.getBytes());
        String change = getChange(bb.getLeft(), bb.getRight());

        System.out.println("Rs " + change);
        DecimalFormat df = new DecimalFormat();
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator(',');
        df.setDecimalFormatSymbols(symbols);
        BigDecimal dd = BigDecimal.valueOf(df.parse(change).doubleValue()).setScale(2, RoundingMode.CEILING);
        System.out.println("Dd " + dd);
    }

    public static Pair<AutoPilot, VTDNav> getNavigator(byte[] xml) {
        Pair<AutoPilot, VTDNav> pair = new Pair<>();
        try {
            VTDGen vg = new VTDGen();
            vg.setDoc(xml);
            vg.parse(true);
            VTDNav vn = vg.getNav();
            AutoPilot ap = new AutoPilot(vn);

            pair.setLeft(ap);
            pair.setRight(vn);
        } catch (Exception ex) {
            System.out.println(ExceptionUtils.getFullStackTrace(ex));
        }

        return pair;
    }

    public static String getChange(AutoPilot ap, VTDNav vn) {
        String path = "//Valute[@ID='R01235']/Value";
        String sms = null;
        try {
            ap.resetXPath();
            ap.selectXPath(path);
            if (ap.evalXPath() != -1) {
                sms = vn.toRawString(vn.getText());
            }
            ap.resetXPath();
        } catch (Exception ex) {
        }
        return sms;
    }

}
