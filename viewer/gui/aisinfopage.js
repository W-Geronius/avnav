/**
 * Created by andreas on 02.05.14.
 */
avnav.provide('avnav.gui.AisInfoPage');




/**
 *
 * @constructor
 */
avnav.gui.AisInfoPage=function(){
    avnav.gui.Page.call(this,'aisinfopage',
        {
            eventlist:[avnav.nav.NavEvent.EVENT_TYPE],
            returnOnClick: true
        }
    );
    /**
     * @private
     * @type {avnav.nav.AisData}
     */
    this.aishandler=null;

    /**
     * @private
     * @type {undefined}
     */
    this.mmsi=undefined;

};
avnav.inherits(avnav.gui.AisInfoPage,avnav.gui.Page);

avnav.gui.AisInfoPage.prototype.localInit=function(){
    this.aishandler=this.navobject.getAisData();
};
avnav.gui.AisInfoPage.prototype.showPage=function(options) {
    if (!this.gui) return;
    this.mmsi=options.mmsi;
    if (this.mmsi === undefined) {
        var current=this.aishandler.getNearestAisTarget();
        if (current) this.mmsi=current.mmsi;
    }
    this.fillData(true);
};

avnav.gui.AisInfoPage.prototype.getCurrentTarget=function(){
    var current=this.aishandler.getAisByMmsi(this.mmsi);
    var warning=this.aishandler.getNearestAisTarget();
    if (warning && warning.warning){
        this.mmsi=warning.mmsi;
        return warning;
    }
    return current;
};
avnav.gui.AisInfoPage.prototype.fillData=function(initial){
    var currentObject=this.getCurrentTarget();
    if (! this.aishandler || currentObject === undefined){
        this.returnToLast();
        return;
    }
    if (currentObject.warning){
        this.selectOnPage('.avn_aisStatus').addClass('avn_ais_warning');
    }
    else{
        this.selectOnPage('.avn_aisStatus').removeClass('avn_ais_warning');
        if (currentObject.nearest){
            this.selectOnPage('.avn_aisStatus').removeClass('avn_ais_info_normal');
            this.selectOnPage('.avn_aisStatus').addClass('avn_ais_info_first');
        }
        else{
            this.selectOnPage('.avn_aisStatus').addClass('avn_ais_info_normal');
            this.selectOnPage('.avn_aisStatus').removeClass('avn_ais_info_first');
        }
    }

    var self=this;
    $("#avi_ais_infopage_inner").show();
    this.selectOnPage(".avn_ais_data").each(function(idx,el){
        var name=$(this).attr('data-name');
        if (! name) return;
        var val=self.aishandler.formatAisValue(name,currentObject);
        $(this).text(val);
    });


};


avnav.gui.AisInfoPage.prototype.hidePage=function(){

};
//-------------------------- Buttons ----------------------------------------

avnav.gui.AisInfoPage.prototype.btnAisInfoNearest=function (button,ev){
    this.aishandler.setTrackedTarget(0);
    var pos=this.aishandler.getAisPositionByMmsi(this.aishandler.getTrackedTarget());
    if (pos)this.gui.map.setCenter(pos);
    this.gui.showPage('navpage');
    log("Nearest clicked");
};

avnav.gui.AisInfoPage.prototype.btnAisInfoList=function (button,ev){
    log("List clicked");
    this.aishandler.setTrackedTarget(this.mmsi);
    this.gui.showPage('aispage',{returnpage:this.returnpage});
};
avnav.gui.AisInfoPage.prototype.btnAisInfoLocate=function (button,ev){
    log("Locate clicked");
    if (this.mmsi === undefined) return;
    var pos=this.aishandler.getAisPositionByMmsi(this.mmsi);
    if (pos)this.gui.map.setCenter(pos);
    this.gui.map.setGpsLock(false);
    this.gui.showPage('navpage');
};
/**
 * create the page instance
 */
(function(){
    //create an instance of the status page handler
    var page=new avnav.gui.AisInfoPage();
}());

