namespace AccessDemo2s
{
    partial class FirstEnterForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.cmb_Status = new System.Windows.Forms.ComboBox();
            this.label3 = new System.Windows.Forms.Label();
            this.cmb_Door = new System.Windows.Forms.ComboBox();
            this.label1 = new System.Windows.Forms.Label();
            this.label4 = new System.Windows.Forms.Label();
            this.txt_Index = new System.Windows.Forms.TextBox();
            this.chb_FirstEnter = new System.Windows.Forms.CheckBox();
            this.label7 = new System.Windows.Forms.Label();
            this.btn_Set = new System.Windows.Forms.Button();
            this.btn_Get = new System.Windows.Forms.Button();
            this.SuspendLayout();
            // 
            // cmb_Status
            // 
            this.cmb_Status.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_Status.DropDownWidth = 140;
            this.cmb_Status.FormattingEnabled = true;
            this.cmb_Status.Items.AddRange(new object[] {
            "Unknown()",
            "KeepOpen()",
            "Normal()"});
            this.cmb_Status.Location = new System.Drawing.Point(177, 115);
            this.cmb_Status.Name = "cmb_Status";
            this.cmb_Status.Size = new System.Drawing.Size(118, 22);
            this.cmb_Status.TabIndex = 25;
            // 
            // label3
            // 
            this.label3.Location = new System.Drawing.Point(50, 112);
            this.label3.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label3.Name = "label3";
            this.label3.Size = new System.Drawing.Size(120, 31);
            this.label3.TabIndex = 24;
            this.label3.Text = "FirstEnterStatus():";
            // 
            // cmb_Door
            // 
            this.cmb_Door.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.cmb_Door.FormattingEnabled = true;
            this.cmb_Door.Location = new System.Drawing.Point(177, 25);
            this.cmb_Door.Name = "cmb_Door";
            this.cmb_Door.Size = new System.Drawing.Size(118, 22);
            this.cmb_Door.TabIndex = 23;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Location = new System.Drawing.Point(72, 28);
            this.label1.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(98, 14);
            this.label1.TabIndex = 22;
            this.label1.Text = "Door():";
            // 
            // label4
            // 
            this.label4.Location = new System.Drawing.Point(26, 161);
            this.label4.Margin = new System.Windows.Forms.Padding(5, 0, 5, 0);
            this.label4.Name = "label4";
            this.label4.Size = new System.Drawing.Size(144, 43);
            this.label4.TabIndex = 26;
            this.label4.Text = "FirstEnterTimeIndex():";
            // 
            // txt_Index
            // 
            this.txt_Index.Location = new System.Drawing.Point(177, 166);
            this.txt_Index.Margin = new System.Windows.Forms.Padding(5);
            this.txt_Index.Name = "txt_Index";
            this.txt_Index.Size = new System.Drawing.Size(118, 23);
            this.txt_Index.TabIndex = 27;
            // 
            // chb_FirstEnter
            // 
            this.chb_FirstEnter.AutoSize = true;
            this.chb_FirstEnter.Location = new System.Drawing.Point(177, 72);
            this.chb_FirstEnter.Name = "chb_FirstEnter";
            this.chb_FirstEnter.Size = new System.Drawing.Size(15, 14);
            this.chb_FirstEnter.TabIndex = 29;
            this.chb_FirstEnter.UseVisualStyleBackColor = true;
            // 
            // label7
            // 
            this.label7.AutoSize = true;
            this.label7.Location = new System.Drawing.Point(16, 72);
            this.label7.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label7.Name = "label7";
            this.label7.Size = new System.Drawing.Size(154, 14);
            this.label7.TabIndex = 28;
            this.label7.Text = "FirstEnter():";
            // 
            // btn_Set
            // 
            this.btn_Set.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Set.Location = new System.Drawing.Point(195, 222);
            this.btn_Set.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Set.Name = "btn_Set";
            this.btn_Set.Size = new System.Drawing.Size(100, 24);
            this.btn_Set.TabIndex = 31;
            this.btn_Set.Text = "Set()";
            this.btn_Set.UseVisualStyleBackColor = true;
            this.btn_Set.Click += new System.EventHandler(this.btn_Set_Click);
            // 
            // btn_Get
            // 
            this.btn_Get.Font = new System.Drawing.Font("", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_Get.Location = new System.Drawing.Point(46, 222);
            this.btn_Get.Margin = new System.Windows.Forms.Padding(4);
            this.btn_Get.Name = "btn_Get";
            this.btn_Get.Size = new System.Drawing.Size(100, 24);
            this.btn_Get.TabIndex = 30;
            this.btn_Get.Text = "Get()";
            this.btn_Get.UseVisualStyleBackColor = true;
            this.btn_Get.Click += new System.EventHandler(this.btn_Get_Click);
            // 
            // FirstEnterForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(331, 279);
            this.Controls.Add(this.btn_Set);
            this.Controls.Add(this.btn_Get);
            this.Controls.Add(this.chb_FirstEnter);
            this.Controls.Add(this.label7);
            this.Controls.Add(this.label4);
            this.Controls.Add(this.txt_Index);
            this.Controls.Add(this.cmb_Status);
            this.Controls.Add(this.label3);
            this.Controls.Add(this.cmb_Door);
            this.Controls.Add(this.label1);
            this.Font = new System.Drawing.Font("", 10.5F);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.Name = "FirstEnterForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "FirstEnter()";
            this.Load += new System.EventHandler(this.FirstEnterForm_Load);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.ComboBox cmb_Status;
        private System.Windows.Forms.Label label3;
        private System.Windows.Forms.ComboBox cmb_Door;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.Label label4;
        private System.Windows.Forms.TextBox txt_Index;
        private System.Windows.Forms.CheckBox chb_FirstEnter;
        private System.Windows.Forms.Label label7;
        private System.Windows.Forms.Button btn_Set;
        private System.Windows.Forms.Button btn_Get;
    }
}