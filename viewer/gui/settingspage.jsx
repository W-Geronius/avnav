/**
 * Created by Andreas on 01.06.2014.
 */
var Store=require('../util/store');
var React=require('react');
var ReactDOM=require('react-dom');
var ItemUpdater=require('../components/ItemUpdater.jsx');
var ItemList=require("../components/ItemList.jsx");
var OverlayDialog=require('../components/OverlayDialog.jsx');
var ColorPicker=require('../components/ColorPicker.jsx');
require('react-color-picker/index.css');

var keys={
    panelVisibility: 'panelVisibility', //header|items|both
    sectionItems: 'sectionItems',
    activeItems: 'activeItems',
    currentValues: 'currentValues'
};

var settingsSections={
    Layer:      ["layers.ais","layers.track","layers.nav","layers.boat","layers.grid","layers.compass","layers.measures"],
    UpdateTimes:["positionQueryTimeout","trackQueryTimeout","aisQueryTimeout" ],
    Layout:     ["baseFontSize","widgetFontSize","allowTwoWidgetRows","showClock","style.buttonSize","nightFade","nightChartFade"],
    AIS:        ["aisDistance","aisWarningCpa","aisWarningTpa","aisTextSize","style.aisNormalColor","style.aisNearestColor","style.aisWarningColor","aisBrowserWorkaround"],
    Navigation: ["bearingColor","bearingWidth","navCircleColor","navCircleWidth","navCircle1Radius","navCircle2Radius","navCircle3Radius","courseAverageTolerance","gpsXteMax"],
    Track:      ["trackColor","trackWidth","trackInterval","initialTrackLength"],
    Route:      ["routeColor","routeWidth","routingTextSize","routeApproach","routeShowLL"]
};

var sectionSelectors={
    selected: 'avn_selectedItem'
};

/**
 *
 * @constructor
 */
var Settingspage=function(){

    this.store=new Store();

    this.sectionItems=[];

    avnav.gui.Page.call(this,'settingspage');
    this.store.register(this,keys.sectionItems,keys.currentValues);
    this.hasChanges=false;
};
avnav.inherits(Settingspage,avnav.gui.Page);

/**
 * the local init called from the base class when the page is instantiated
 */
Settingspage.prototype.localInit=function(){
    var self=this;
    this.sectionItems=[];
    var idx=0;
    for (var section in settingsSections){
        this.sectionItems.push({name:section,key:idx,data:settingsSections[section]});
        idx++;
    }
    var SectionItem=function(properties){
        return <div className={properties.addClass+ " avn_list_entry"} onClick={properties.onClick}>{properties.name}</div>
    };
    var SettingsItem=function(properties){
        if (properties.type == avnav.util.PropertyType.CHECKBOX) {
            return <div className={properties.addClass+ " avn_list_entry"}>
                <label>{properties.label}</label>
                <input type="checkbox" checked={properties.value?true:false}
                       onChange={function(ev){
                        self.changeValue(properties.name,ev.target.checked);}
                        }/>
            </div>
        }
        if(properties.type == avnav.util.PropertyType.RANGE){
            return <div className={properties.addClass+ " avn_list_entry"}
                        onClick={function(ev){
                            self.rangeItemDialog(properties);
                        }}>
                    <div className="avn_description">{properties.label}</div>
                    <div className="avn_value">{properties.value}</div>
                </div>;
        }
        if(properties.type == avnav.util.PropertyType.COLOR){
            var style={
                backgroundColor: properties.value
            };

            return <div className={properties.addClass+ " avn_list_entry avn_colorSelector"}
                        onClick={function(ev){
                            self.colorItemDialog(properties);
                        }}>
                <div className="avn_description">{properties.label}</div>
                <div className="avn_colorValue" style={style}></div><div className="avn_value">{properties.value}</div>
            </div>;
        }
        else{
            return(<div className={properties.addClass+ " avn_list_entry"}>{properties.label}</div>);
        }
    };
    var sectionClick=function(item){
        var current=self.store.getData(keys.sectionItems,{}).selectors;
        if (current && current[sectionSelectors.selected] == item.key) return;
        self.store.updateSubItem(keys.sectionItems,sectionSelectors.selected,item.key,'selectors');
    };
    var SectionList=ItemUpdater(ItemList,this.store,keys.sectionItems);
    var SettingsList=ItemUpdater(ItemList,this.store,keys.activeItems);
    var Settings=ItemUpdater(React.createClass({
        render: function(){
            var leftVisibile=true;
            var rightVisible=true;
            return (
                <div className="avn_panel_fill">
                    { leftVisibile && <div className="avn_leftSection"><SectionList
                        className="avn_scroll"
                        itemClass={SectionItem}
                        onItemClick={sectionClick}
                        itemList={self.sectionItems}
                                 /></div>}
                    {rightVisible && <div className="avn_rightSection"><SettingsList
                        className="avn_scroll"
                        itemClass={SettingsItem}
                    /></div>}
                </div>
            );
        }
    }),this.store,keys.panelVisibility);
    ReactDOM.render(React.createElement(Settings,{}),this.selectOnPage('.avn_left_inner')[0]);
};

Settingspage.prototype.rangeItemDialog=function(item){
    var self=this;
    var Dialog=React.createClass({
        valueChange: function(ev){
            this.setState({value: ev.target.value});
        },
        getInitialState: function(){
            return({
                value: item.value
            });
        },
        buttonClick:function(ev){
            var button=ev.target.name;
            if (button == 'ok'){
                if (this.state.value < item.values[0]|| this.state.value > item.values[1]){
                    self.toast("out of range");
                    return;
                }
                self.changeValue(item.name,this.state.value);
            }
            if (button == 'reset'){
                this.setState({
                    value: self.gui.properties.getValueByName(item.name)
                });
                return;
            }
            self.hideToast();
            this.props.closeCallback();
        },
        render:function() {
            var range=item.values[0]+"..."+item.values[1];
            return(
                    <div className="avn_settingsDialog">
                        <h3><span >{item.label}</span></h3>
                        <div className="avn_settingsRange">Range: {range}</div>
                        <div>
                            <label >Value
                                <input type="number"
                                       min={item.values[0]}
                                       max={item.values[1]}
                                       step={item.values[2]||1}
                                       name="value" onChange={this.valueChange} value={this.state.value}/>
                            </label>
                        </div>
                        <button name="ok" onClick={this.buttonClick}>OK</button>
                        <button name="cancel" onClick={this.buttonClick}>Cancel</button>
                        <button name="reset" onClick={this.buttonClick}>Reset</button>
                        <div className="avn_clear"></div>
                    </div>
            );
        }
    });
    OverlayDialog.dialog(Dialog,this.getDialogContainer(),{

    });
};

Settingspage.prototype.changeValue=function(name,value){
    var oldValue=this.store.getData(keys.currentValues,{})[name];
    if (oldValue && oldValue == value) return;
    this.hasChanges=true;
    this.store.updateSubItem(keys.currentValues,name,value);
};
Settingspage.prototype.colorItemDialog=function(item){
    var self=this;
    var colorDialogInstance;
    var Dialog=React.createClass({
        valueChange: function(ev){
            this.setState({value: ev.target.value});
        },
        getInitialState: function(){
            return({
                value: item.value
            });
        },
        buttonClick:function(ev){
            var button=ev.target.name;
            if (button == 'ok'){
                self.changeValue(item.name,this.state.value);
            }
            if (button == 'reset'){
                this.setState({
                    value: self.gui.properties.getValueByName(item.name)
                });
                return;
            }
            this.props.closeCallback();
        },
        onDrag: function(color,c){
            this.setState({
                value: color
            })
        },
        colorInput: function(ev){
            this.setState({
                value:ev.target.value
            })
        },
        render:function() {
            var style={
                backgroundColor:this.state.value,
                width: 30,
                height: 30
            };
            var pickerProperties={
                saturationWidth: 250,
                saturationHeight: 250,
                hueWidth: 30
            };
            return (
                <div className="avn_settingsDialog avn_colorDialog">
                    <h3><span >{item.label}</span></h3>
                    <ColorPicker value={this.state.value} onDrag={this.onDrag} {...pickerProperties}/>
                    <div className="avn_colorFrame">
                        <div style={style} className="avn_colorValue"></div>
                        <input className="avn_colorName"
                               onChange={this.colorInput}
                               value={this.state.value}/>
                    </div>
                    <button name="ok" onClick={this.buttonClick}>OK</button>
                    <button name="cancel" onClick={this.buttonClick}>Cancel</button>
                    <button name="reset" onClick={this.buttonClick}>Reset</button>
                    <div className="avn_clear"></div>
                </div>
            );
        }

    });
    OverlayDialog.dialog(Dialog,this.getDialogContainer());
};




Settingspage.prototype.showPage=function(options){
    if (!this.gui) return;
    var self=this;
    this.store.resetData();
    this.store.updateSubItem(keys.sectionItems,sectionSelectors.selected,0,'selectors');
    this.resetValues();
};

Settingspage.prototype.resetValues=function(opt_defaults){
    var self=this;
    var newValues={};
    for (var section in settingsSections){
        var items=settingsSections[section];
        items.forEach(function(item){
            var description=self.gui.properties.getDescriptionByName(item);
            var value;
            if (! opt_defaults)value =self.gui.properties.getValue(description);
            else value= description.defaultv;
            newValues[item]=value;
        })
    }
    this.store.storeData(keys.currentValues,newValues);
    this.hasChanges=opt_defaults?true:false;
};
/**
 * called when the section selection has changed
 */
Settingspage.prototype.dataChanged=function(){
    var selectedSection=this.store.getData(keys.sectionItems,{}).selectors;
    var selectedIndex=selectedSection?selectedSection[sectionSelectors.selected]:0;
    this.createItemList(this.sectionItems[selectedIndex].name);
};
Settingspage.prototype.createItemList=function(sectionName){
    var self=this;
    var values=this.store.getData(keys.currentValues,{});
    var items=settingsSections[sectionName]||[];
    var newItemList=[];
    var description;
    items.forEach(function(item){
        description=self.gui.properties.getDescriptionByName(item);
        newItemList.push(avnav.assign({},{
            name:item},description,{value:values[item]}));
    });
    this.store.updateSubItem(keys.activeItems,'itemList',newItemList);
};


Settingspage.prototype.hidePage=function(){

};



//-------------------------- Buttons ----------------------------------------


/**
 * activate settings and go back to main
 * @private
 */
Settingspage.prototype.btnSettingsOK=function(button,ev){
    avnav.log("SettingsOK clicked");
    var currentData=this.store.getData(keys.currentValues);
    for (var idx in currentData){
        var val=currentData[idx];
        this.gui.properties.setValueByName(idx,val);
    }
    this.gui.properties.saveUserData(); //write changes to cookie
    this.gui.properties.updateLayout();  //update the layout based on less
    $(document).trigger(avnav.util.PropertyChangeEvent.EVENT_TYPE,new avnav.util.PropertyChangeEvent(this.gui.properties));
    this.gui.showPage('mainpage');
};

Settingspage.prototype.btnSettingsDefaults=function(button,ev) {
    avnav.log("SettingsDefaults clicked");
    var self=this;
    OverlayDialog.confirm("reset all settings?",this.getDialogContainer()).then(function() {
        self.resetValues(true);
    });
};

Settingspage.prototype.btnSettingsAndroid=function(button,ev) {
    avnav.log("SettingsAndroid clicked");
    var self=this;
    if (this.hasChanges) {
        this.gui.showPage('mainpage');
        avnav.android.showSettings();
    }
    else{
        OverlayDialog.confirm("discard changes?",this.getDialogContainer()).then(function() {
            self.gui.showPage('mainpage');
            avnav.android.showSettings();
        });
    }
};

Settingspage.prototype.btnCancel=function(button,ev) {
    if (! this.hasChanges) {
        this.returnToLast();
        return;
    }
    var self=this;
    OverlayDialog.confirm("discard changes?",this.getDialogContainer()).then(function() {
        self.returnToLast();
    });
};


(function(){
    //create an instance of the status page handler
    var page=new Settingspage();
}());

